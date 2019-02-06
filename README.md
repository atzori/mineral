mineral: Computing Optimized Recursive SPARQL Queries with Aggregates
=====================================================================

**NOTE: THIS IS A DEVELOPMENT VERSION (WORK IN PROGRESS)** 


**mineral**, loosely for *min* (function) *e*nhanced *r*ecursion through *a*ccumu*l*ation is a custom SPARQL function for optimized recursion within SPARQL with aggregates, inspired by the work by Prof. [Carlo Zaniolo](http://web.cs.ucla.edu/~zaniolo/) on [*Fixpoint semantics and optimization of recursive Datalog programs with aggregates*](https://doi.org/10.1017/S1471068417000436) and work by Prof. [Maurizio Atzori](http://atzori.webofcode.org/) on [*Computing recursive SPARQL queries*](https://doi.org/10.1109/ICSC.2014.54) (see on [github](https://github.com/atzori/runSPARQL)).



Functions and usage
-------------------
We developed the following two functions (the `wfn` prefix stands for `<http://webofcode.org/wfn/>`):

  - `wfn:min(?query, [, ?i0, ?i1, ...])` - memoization is used to cut off loops and increase speed on already-seen states in the search space
  - `wfn:mina(?query, ?a [, ?i0, ?i1, ...])` - Zaniolo's inspired technique is used to cut off unnecessary branches in the search space including loops (parameter `a` is used as an accumulator, see below)
 
In both versions, optimization can be disabled via server configuration.


Install and compile *(tested with Fuseki 3.8.0)*
-------------------
1. ensure you have OpenJDK 8 or later correctly installed
2. download and extract [Apache Fuseki 2](https://jena.apache.org/download/#apache-jena-fuseki) on directory `./fuseki`
3. compile Java source code of functions: `./compile`
4. run fuseki using testing settings: `./run-fuseki`
    - fuseki server settings in `config/config.ttl` 
    - initial data in `config/dataset.ttl` 
    - log4j settings in `config/log4j.properties`
5. go to: [http://127.0.0.1:3030](http://127.0.0.1:3030)



Examples
--------

### Computing the Factorial
In the following it is shown how to compute the factorial of 3.

With **min**:

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { "BIND ( IF(?i0 <= 0, 1, ?i0 * :min(?query, ?i0-1)) AS ?result)" }

        # actual call of the recursive query 
        BIND(:min(?query, 3) AS ?result)
    } 


With **mina**:

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { "BIND ( IF(?i0 <= 0, ?n, :mina(?query, ?a * ?i0, ?i0-1)) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mina(?query, 1, 3) AS ?result)
    } 


### Computing Fibonacci
Compute the fibonacci number 

With **min**:

    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
           "BIND ( IF(?i0 <= 2, 1, :min(?query, ?i0 -1) + :min(?query, ?i0 -2) ) AS ?result)" }

        # actual call of the recursive query 
        BIND(:min(?query, 6) AS ?result)
    } 

With **mina**:

    # for explanation, see: https://marcaube.ca/2016/03/optimizing-a-fibonacci-function
    # (?i0=prev, ?i1=n, ?a=acc)
    PREFIX : <http://webofcode.org/wfn/>
    
    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
           "BIND ( IF(?i0 <= 0, ?a, :mina(?query, ?a+ ?i0, ?a, ?i1 -1 ) ) AS ?result)" }

        # actual call of the recursive query 
        BIND(:mina(?query, 0, 1, 6) AS ?result)
    } 




### Graph search: shortest distance between 2 nodes
In the following it is shown how to compute the shortest distance between two nodes ([dbo:PopulatedPlace](http://dbpedia.org/ontology/PopulatedPlace) and [dbo:Village](http://dbpedia.org/ontology/Village)).

With **min**:

    PREFIX : <http://webofcode.org/wfn/>

    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
            "OPTIONAL { ?i0 <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?next } BIND( IF(?i0 = <http://dbpedia.org/ontology/PopulatedPlace>, 0 , 1 + wfn:min(?query, ?next)) AS ?result)" }

        # actual call of the recursive query 
        BIND( :min(?query, <http://dbpedia.org/ontology/Village>) AS ?result)
    } 



With **mina**:

    PREFIX wfn: <http://webofcode.org/wfn/>

    SELECT ?result { 
        # bind variables to parameter values 
        VALUES ?query { 
            "OPTIONAL { ?i0 <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?next } BIND( IF(?i0 = <http://dbpedia.org/ontology/PopulatedPlace>, ?a, :mina(?query, ?n+1, ?next)) AS ?result)" }

        # actual call of the recursive query 
        BIND( :mina(?query, 0, <http://dbpedia.org/ontology/Village>) AS ?result)
    } 








