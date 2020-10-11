/* 
 *  Copyright 2014-2019. Maurizio Atzori <atzori@unica.it>
 *
 */
 
package org.webofcode.wfn;

import org.apache.jena.sparql.function.*;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.nodevalue.NodeValueInteger;
import org.apache.jena.sparql.function.FunctionRegistry;


import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.expr.Expr;

import org.apache.jena.sparql.ARQConstants;
import org.apache.jena.rdfconnection.*;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.client.HttpClient;
import org.apache.jena.graph.NodeFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.*;
import static java.util.stream.Collectors.*;

import org.apache.jena.atlas.lib.Lib;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.sparql.ARQInternalErrorException;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.ExprList;

/**
 * This class implements the mr function to be used within SPARQL queries in the Apache Fuseki 2 triplestore.
*/
public class mr extends FunctionBase {
    static Logger log = LoggerFactory.getLogger(mr.class);
    
    static final String optimization = getOptimizationStrategy(); // parameter specifies the default optimization strategy
    static final boolean CACHE_ENABLED = !optimization.equals("none");
    static final String ENDPOINT = System.getProperty("mineral.endpoint");
    static final String FN_NAME = "http://webofcode.org/wfn/mr";
    static int nInstances = 0;
    int instanceID;
    static final Map<String,NodeValue> cache = new ConcurrentHashMap<>();
    static final NodeValue VISITED = NodeValue.makeNode(NodeFactory.createBlankNode("VISITED"));
    static String TEMPLATE;


    static NodeValue bestSolutionSoFar = null;

    static String getOptimizationStrategy() {
        final String defaultOptimization = "memo";
        
        String opt = System.getenv​("OPT");
        if (opt==null) opt = System.getenv​("opt");
        if (opt==null) opt = System.getProperty("mineral.opt");
        if (opt!=null && !Arrays.asList("none","memo","fast").contains(opt))  {
            log.error("optimization must be one of: 'none', 'memo', 'fast'. Non-valid '{}' value was specified. Using default {}", opt, defaultOptimization);
            opt = defaultOptimization;
        }
        
        if (opt==null)  {
            log.warn("no optimization specified. Using default {}", defaultOptimization);
            opt = defaultOptimization;
        }
        return opt.toLowerCase();
    }

    public static void init() {
        log.info("Registering function {}", FN_NAME);
        FunctionRegistry.get().put(FN_NAME, mr.class);
        //log.debug(System.getProperties().toString());

        if(ENDPOINT==null) {
            log.error("No endpoint specified! This is not going to work.");
        }
        log.info("Endpoint set to {}", ENDPOINT);   
        log.info("Optimization set to {}", optimization);
        log.info("Cache results set to {}", CACHE_ENABLED);
        
        // load sparql template file     
        try {
            TEMPLATE = String.join("\n", Files.readAllLines(Paths.get(System.getProperty("mineral.sparql_template"))));
        } catch(Exception e) {
            log.error("Error while reading sparql template");
        }
        //log.debug("Template set to {}", TEMPLATE);
        
    }
    public mr() {
        nInstances += 1;
        instanceID = nInstances;
        //log.debug("Creating instance #{}", nInstances);
    }


    @Override
    public void checkBuild(String uri, ExprList args) { 
        if ( args.size() < 1 )
            throw new QueryBuildException("Function '"+Lib.className(this)+"' takes at least one argument (a query snippet)") ;
    }

    public NodeValue exec(List<NodeValue> args) { 
    
        if ( args == null )
            // The contract on the function interface is that this should not happen.
            throw new ARQInternalErrorException(Lib.className(this)+": Null args list") ;
        
        if ( args.size() < 1 )
            throw new ExprEvalException(Lib.className(this)+": Wrong number of arguments: Wanted 1+, got "+args.size()) ;
        

        String querySnippet = args.get(0).toString();
        List<NodeValue> fnArgs = new ArrayList<>(args);
        fnArgs.remove(0);
        log.info("Calling mr#{} with {}", instanceID, fnArgs);
            

        String varNames = IntStream.range(0,fnArgs.size()).mapToObj(i -> "?i"+i).collect(Collectors.joining(" "));
        String varValues = args.stream().map(e -> e.toString()).collect(Collectors.joining("\n"));

        String bindings = String.format("VALUES (?query %s) { (\n%s\n)}", varNames, varValues);
        
        String query = TEMPLATE
            .replace("%query_snippet%", querySnippet.trim().replaceAll("^\\\"(.*)\\\"$","$1"))
            .replace("%bindings%", bindings);
                    
                
        NodeValue result = null;  

        // try to avoid loops    
        String key = String.join("|",args.stream().map(e->e.toString()).collect(toList())); //String.format("%s|%s", i0, query); // endpoint
        if (CACHE_ENABLED) {
            if(cache.containsKey(key)) {
                result = cache.get(key); 
                log.info("Loop found for i={}, result={}", fnArgs, result);
                return result==VISITED? nodeNone(): result; 
            } else 
                cache.put(key, VISITED); // mark as "visited (but not resolved yet)" 
        }
        
        
        // fast optimization
        if (optimization.equals("fast") && bestSolutionSoFar != null && args.get(1) instanceof NodeValue) {
            NodeValue acc = (NodeValue) args.get(1); // accumulator
            // print runtime type info on acc.
            //if (bestSolutionSoFar.toString().compareTo(acc.toString()) <= 0) {
            if(NodeValue.compareAlways(bestSolutionSoFar, acc) <= 0) {  // ORDERBY ordering
                //log.debug(bestSolutionSoFar.getClass().getName());
                //log.debug(acc.getClass().getName());
                
            
                log.info("Branch of mr#{} was CUT by fast optimization", instanceID);
                return nodeNone(); // acc; // cut this computation branch (optimization)
            }
            
        }
     
        
        
        List<RDFNode> solutions = executeQuery(query, ENDPOINT); //service); 
        
        if (solutions.size()>0) {
            RDFNode node = solutions.get(0); // only the first solution is used
            result =(node == null) ? nodeNone() : NodeValue.makeNode(node.asNode()); // check why node can/could(?) be null
        } else {
	        log.warn("Resultset was empty");
		    result = nodeNone(); //nvNothing; // Expr.NONE; //NodeValue.makeString("no result!");
        }

	    //log.info("Result was {}", result);
	    log.info("Result of mr#{} for {} was {}, solutions={}", instanceID, fnArgs, result, solutions);
        if(CACHE_ENABLED) cache.put(key, result);
        
                
        // fast optimization
        if (optimization.equals("fast")) {
            if (bestSolutionSoFar == null ||  NodeValue.compareAlways(bestSolutionSoFar, result) > 0) {
                bestSolutionSoFar = result; // NodeValue
                log.info("From mr#{} new best solution so far {} for fast optimization", instanceID, bestSolutionSoFar);
            }
            
        }
        return result;        

    }
    
    private static List<RDFNode> executeQuery(String query, String service) {
    
        //QueryExecution qe = QueryExecutionFactory.create(query); // local call not working without specifying a dataset        
        //log.info(""+ARQConstants.sysCurrentDataset ); //symDatasetDefaultGraphs.toString()) ;


	    List<RDFNode> solutions = null;
       /* try ( RDFConnection conn = RDFConnectionFactory.connect("http://127.0.0.1:3030/ds") ) {

            log.info("within try x1");

            ResultSet rs = conn.query(query).execSelect() ;
            log.info("within try x2");
            solutions = resultSet2List(rs);
            //return ResultSetFactory.copyResults(rs) ;


        }*/


        /*
            An HttpClient is necessary since default client only accepts a limited number of connections (no more than 5)
            https://stackoverflow.com/questions/49661698/how-can-i-change-the-number-of-connections-par-route-in-jena-more-than-5
        */
        HttpClient client = HttpClientBuilder.create().build();
	    
	    try (QueryExecution qe = 
	            QueryExecutionFactory.sparqlService(service, query, client)
	            //QueryExecutionFactory.create(query) //, Dataset.getDefaultModel()) 
	      ) {
	      
            ResultSet rs = qe.execSelect(); // sparql query is actually executed remotely on the endpoint
            //rs = ResultSetFactory.copyResults(rs) ;
            solutions = resultSet2List(rs);            
        }
        return solutions;
    
    }
    
    //@SuppressWarnings( "deprecation" )
    private static NodeValue nodeNone() {
        //return (NodeValue) NodeValue.NONE;
        //return NodeValue.nvNothing; // equivalent to: NodeValue.makeNode(NodeFactory.createBlankNode(strForUnNode)) 
        //return NodeValue.NONE.eval(null,null);
        return NodeValue.makeNode(NodeFactory.createBlankNode("noResult")); // this is working
        //return new NodeValueInteger(-999);
    }
    
    /** convert a ResultSet with column "result" into a List of RDFNode */
    private static List<RDFNode> resultSet2List(ResultSet rs) {
        if (rs == null) throw new RuntimeException("null as result");
        
        List<RDFNode> solutions = new ArrayList<>();	        
	    	        
        while(rs.hasNext()) {
	        QuerySolution res = rs.next();
	        if(res == null) throw new RuntimeException("querysolution was null");
	        RDFNode node = res.get("result");
	        solutions.add(node);	        
        }
        
        
        return solutions;
    }        

}
