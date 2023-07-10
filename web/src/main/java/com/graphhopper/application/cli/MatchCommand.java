/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.graphhopper.application.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Pattern;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.application.GraphHopperServerConfiguration;
import com.graphhopper.jackson.Gpx;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPoint3D;

import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

public class MatchCommand extends ConfiguredCommand<GraphHopperServerConfiguration> {

    public MatchCommand() {
        super("match", "map-match one or more gpx files");
    }

    @Override
    public void configure(Subparser subparser) {
        subparser.addArgument("csv")
                .type(File.class)
                .required(true)
                .nargs("+")
                .help("CSV file");
        subparser.addArgument("--file")
                .required(true)
                .help("application configuration file");
        subparser.addArgument("--profile")
                .type(String.class)
                .required(true)
                .help("profile to use for map-matching (must be configured in configuration file)");
        subparser.addArgument("--gps_accuracy")
                .type(Integer.class)
                .required(false)
                .setDefault(40);
        subparser.addArgument("--transition_probability_beta")
                .type(Double.class)
                .required(false)
                .setDefault(2.0);
    }

    @Override
    protected Argument addFileArgument(Subparser subparser) {
        // Never called, but overridden for clarity:
        // In this command, we want the configuration file parameter to be a named argument,
        // not a positional argument, because the positional arguments are the gpx files,
        // and we configure it up there ^^.
        // Must be called "file" because superclass gets it by name.
        throw new RuntimeException();
    }

    @Override
    protected void run(Bootstrap<GraphHopperServerConfiguration> bootstrap, Namespace args, GraphHopperServerConfiguration configuration) {
        GraphHopperConfig graphHopperConfiguration = configuration.getGraphHopperConfiguration();

        GraphHopper hopper = new GraphHopper().init(graphHopperConfiguration);
        hopper.importOrLoad();

        PMap hints = new PMap();
        hints.putObject("profile", args.get("profile"));
        MapMatching mapMatching = MapMatching.fromGraphHopper(hopper, hints);
        mapMatching.setTransitionProbabilityBeta(args.getDouble("transition_probability_beta"));
        mapMatching.setMeasurementErrorSigma(args.getInt("gps_accuracy"));

        StopWatch importSW = new StopWatch();
        StopWatch matchSW = new StopWatch();
        
        IntEncodedValue osmWay = hopper.getEncodingManager().getIntEncodedValue("osm_way_id");

        for (File gpxFile : args.<File>getList("csv")) {
        	String outFile = gpxFile.getAbsolutePath() + ".res.csv";
            try (FileInputStream fis = new FileInputStream(gpxFile);
            	 FileOutputStream fos = new FileOutputStream(outFile)) {
                importSW.start();
                CsvReader csvReader = new CsvReader(fis, Charset.forName("UTF-8"));
                if (!csvReader.readHeaders()) {
                	throw new IllegalArgumentException(String.format("File %s does not contain header", gpxFile.getName()));
                }
                csvReader.setSafetySwitch(false);
                CsvWriter csvWriter = new CsvWriter(fos, ',', Charset.forName("UTF-8"));
                csvWriter.writeRecord(new String []{"id", "order", "origin_lat", "origin_long", "destination_lat", "destination_long", "trip_event", "osm_way_id"});
                
                int order = 1;
                while (csvReader.readRecord()) {
                	String id = csvReader.get("id");
                	String path = csvReader.get("path");
                	List<Observation> measurements = getEntries(path);
                	importSW.stop();
                	matchSW.start();
                	MatchResult mr = mapMatching.match(measurements);
                	matchSW.stop();
                	
                	Graph gr = mr.getGraph();
                	NodeAccess nodeAccess = gr.getNodeAccess();
                	List<EdgeIteratorState> edges = mr.getMergedPath().calcEdges();
					int firstNodeIdx = edges.get(0).getBaseNode();
					order = 1;
                	csvWriter.writeRecord(getNodeEvent(nodeAccess, firstNodeIdx, "start", id, order++));
                	for (EdgeIteratorState ed: edges) {
                		csvWriter.writeRecord(getEdgeEvent(nodeAccess, ed, id, order++, osmWay));
                	}
                	int lastNodeIdx = edges.get(edges.size()-1).getAdjNode();
                	csvWriter.writeRecord(getNodeEvent(nodeAccess, lastNodeIdx, "end", id, order));
                	importSW.start();
                }
                importSW.stop();
                
                System.out.println(gpxFile);
                System.out.println("\texport results to:" + outFile);

                csvWriter.close();
                csvReader.close();
                
            } catch (Exception ex) {
                importSW.stop();
                matchSW.stop();
                System.err.println("Problem with file " + gpxFile);
                ex.printStackTrace(System.err);
            }
        }
        System.out.println("gps import took:" + importSW.getSeconds() + "s, match took: " + matchSW.getSeconds());
    }
    
    private String [] getNodeEvent(NodeAccess nodeAccess, int nodeIndex, String tripEvent,  String id, int order) {
    	return new String[] {id, ""+order, 
    			""+nodeAccess.getLat(nodeIndex),""+nodeAccess.getLon(nodeIndex),
    			""+nodeAccess.getLat(nodeIndex),""+nodeAccess.getLon(nodeIndex), 
    			tripEvent, ""};
    }
    
    private String [] getEdgeEvent(NodeAccess nodeAccess, EdgeIteratorState edge, String id, int order, IntEncodedValue osmWay) {
    	int originIndex = edge.getBaseNode();
    	int destinationIndex = edge.getAdjNode();
    	
    	return new String[] {id, ""+order, 
    			""+nodeAccess.getLat(originIndex),""+nodeAccess.getLon(originIndex),
    			""+nodeAccess.getLat(destinationIndex),""+nodeAccess.getLon(destinationIndex), 
    			"route", ""+edge.get(osmWay)};
    }
    
    public static List<Observation> getEntries(String path) {
    	ArrayList<Observation> gpxEntries = new ArrayList<>();
    	try (Scanner scanner = new Scanner(path).useDelimiter(Pattern.compile("[ \\t\\n,\\[\\]]+")).useLocale(Locale.US)) {
    		
    		while (scanner.hasNextDouble()) {
    			double lon = scanner.nextDouble();
        		double lat = scanner.nextDouble();
        		
        		gpxEntries.add(new Observation(new GHPoint3D(lat, lon, 0)));
    		}
    		
    		/*
    		String lonS = scanner.next();
    		String latS = scanner.next();
    		
    		double lat = Double.parseDouble(latS);
    		double lon = Double.parseDouble(lonS);*/
    		
    	}
        return gpxEntries;
    }

}
