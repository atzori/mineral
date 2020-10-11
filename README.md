mineral: Computing Optimized Recursive SPARQL Queries with Aggregates
=====================================================================

**NOTE: THIS IS A DEVELOPMENT VERSION (WORK IN PROGRESS)** 


**mineral**, loosely for *min* (function) *e*nhanced *r*ecursion through *a*ccumu*l*ation is a custom SPARQL function for optimized recursion within SPARQL with aggregates, inspired by the work by Prof. [Carlo Zaniolo](http://web.cs.ucla.edu/~zaniolo/) on [*Fixpoint semantics and optimization of recursive Datalog programs with aggregates*](https://doi.org/10.1017/S1471068417000436) and work by Prof. [Maurizio Atzori](http://atzori.webofcode.org/) on [*Computing recursive SPARQL queries*](https://doi.org/10.1109/ICSC.2014.54) (see on [github](https://github.com/atzori/runSPARQL)).



Functions and usage
-------------------
We developed the following function (the `wfn` prefix stands for `<http://webofcode.org/wfn/>`):

    wfn:mr(?query [, ?i0, ?i1, ...])

that can be used with different optimization strategies (configurable server-side):
  
  - `none` - no optimization used to cut off search space (may not end up in case of cycles in the graph)
  - `memo` - memoization is used to cut off loops (circles in the graph) and increase speed on already-seen states in the search space
  - `fast` - accumulator-based recursion inspired by Zaniolo's technique is used to cut off unnecessary branches in the search space including loops (the first parameter, `?i0`, must be used as an accumulator to be minimized, see below)
 
In both versions, optimization can be disabled via server configuration.


Install and compile *(tested with Fuseki 3.8.0)*
-------------------
1. ensure you have OpenJDK 8 or later correctly installed
2. download and extract [Apache Fuseki 2](https://jena.apache.org/download/#apache-jena-fuseki) on directory `./fuseki`
3. compile Java source code of functions: `./compile`
4. run fuseki using testing settings: `OPT=memo ./run-fuseki`
    - fuseki server settings in `config/config.ttl` 
    - initial data in `config/dataset.ttl` 
    - log4j settings in `config/log4j.properties`
    - optimization is `memo` by default, use `OPT=fast` for fast optimization or `OPT=none` to disable optimization
5. go to: [http://127.0.0.1:3030](http://127.0.0.1:3030) and run a recursive query (see examples below)




Accumulator
-----------
From [*Functional Programming: Accumulators* on medium.com](https://medium.com/@matthewdimarcantonio/functional-programming-accumulators-95129a28e1d1):

> An accumulator is an additional argument added to a function. As the function that has an accumulator is continually called upon, the result of the computation so far is stored in the accumulator. After the recursion is done, the value of the accumulator itself is often returned unless further manipulation of the data is needed. 

(see [accumulators and folds](https://arothuis.nl/posts/accumulators-and-folds/) for insights on the accumulator pattern and how it relates to recursion)

With `mineral`, in order to use the `fast` optimization, you must use the first parameter `?i0` of the recursive function `:mr` as an accumulator, that is, a monotone non-decreasing value over nested calls. Monotonicity must hold according to `ORDER BY ?i0`, that is, according to natural order in SPARQL. 

In the following a list of examples computing with recursive function both without an accumulator (`fast` optimization strategy cannot be used) and with an accumulator (all optimizations including `fast` can be used).


Examples
--------

### Computing the Factorial
In the following it is shown how to compute the factorial of 3.

Simple, inline (without accumulator):

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        BIND(:mr("BIND ( IF(?i0 <= 0, 1, ?i0 * :mr(?query, ?i0-1)) AS ?result)", 3) AS ?result)
    } 


or alternatively (more verbose but clearer):

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { "BIND ( IF(?i0 <= 0, 1, ?i0 * :mr(?query, ?i0-1)) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mr(?query, 3) AS ?result)
    } 
    

With support for accumulator (parameter `?i0` is an accumulator):

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        BIND(:mr("BIND ( IF(?i1 <= 0, ?i0, :mr(?query, ?i0 * ?i1, ?i1-1)) AS ?result)", 1, 3) AS ?result)
    } 



or verbosely:

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { "BIND ( IF(?i1 <= 0, ?a, :mr(?query, ?i0 * ?i1, ?i1-1)) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mr(?query, 1, 3) AS ?result)
    } 



### Computing Fibonacci
Compute the fibonacci number 

Without accumulator:

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
           "BIND ( IF(?i0 <= 2, 1, :mr(?query, ?i0 -1) + :mr(?query, ?i0 -2) ) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mr(?query, 6) AS ?result)
    } 

With accumulator:

    # for explanation, see: https://marcaube.ca/2016/03/optimizing-a-fibonacci-function
    # (?i0=acc, ?i1=prev, ?i2=n)
    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
           "BIND ( IF(?i2 <= 0, ?i0, :mr(?query, ?i0 + ?i1, ?i0, ?i2 -1 ) ) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mr(?query, 0, 1, 47) AS ?result)
    } 



### Graph search: shortest distance between 2 nodes
In the following it is shown how to compute the shortest distance between two nodes ([dbo:Village](http://dbpedia.org/ontology/Village) and [dbo:Location](http://dbpedia.org/ontology/Location) and ).

Without accumulator:

    PREFIX    : <http://webofcode.org/wfn/>
    PREFIX dbo: <http://dbpedia.org/ontology/> 

    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
            "OPTIONAL { ?i0 <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?next } BIND( IF(?i0 = ?i1, 0 , IF(bound(?next), 1 + wfn:mr(?query, ?next, ?i1), ?a)) AS ?result)" }

        # actual call of the recursive query 
        BIND( :mr(?query, dbo:Village, dbo:Location) AS ?result)
    } 


Note that looking for the maxumum distance (`max` instead of `min`) is also possible by using negative numbers:

    PREFIX    : <http://webofcode.org/wfn/>
    PREFIX dbo: <http://dbpedia.org/ontology/> 

    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
            "OPTIONAL { ?i0 <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?next } BIND( IF(?i0 = ?i1, 0 , IF(bound(?next), -1 + wfn:mr(?query, ?next, ?i1), ?a)) AS ?result)" }

        # actual call of the recursive query 
        BIND( -1 * :mr(?query, dbo:Village, dbo:Location) AS ?result)
    } 


Shortest distance with accumulator:

    PREFIX    : <http://webofcode.org/wfn/>
    PREFIX dbo: <http://dbpedia.org/ontology/> 

    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
            "OPTIONAL { ?i1 <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?next } BIND( IF(?i1 = ?i2, ?i0, :mr(?query, ?i0+1, ?next, ?i2)) AS ?result)" }

        # actual call of the recursive query 
        BIND( :mr(?query, 0, dbo:Village, dbo:Location) AS ?result)
    }





