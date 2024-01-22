# Setting Up the Environment

The following is a list of files in this repository that need to be configured in the real environment:

* smilecdr-write/classes/cdr-config-Write.properties - Config for the write node
* smilecdr-read/classes/cdr-config-Write.properties - Config for the read node
* smilecdr-gateway/classes/cdr-config-Gateway.properties - Config for the gateway node
* smilecdr-gateway/customerlib/logback-smile-custom.xml - Custom logback that reduces logging
* smilecdr-gateway/classes/benchmark/gateway_config.json - Gateway config file
* smilecdr-gateway/classes/benchmark/gateway.jwks - Gateway keystore

In addtion, the interceptors project should be built and made available to each of the 3 nodes:

```
cd interceptors
mvn clean install
```


# Running The Benchmark

First, you need to build the benchmark project.

```bash
cd perftest
mvn clean install
```

### Preparing Search Parameters

The following command creates a unique index SearchParameter which avoids duplicate Organizations being created during the benchmark. You need to run it once when the environment is empty, and then wait 60 seconds for the cluster caches to refresh. Once this has run and the SP is in the database, it does not ever need to be run again.

> java -cp target/perftest.jar CreateUniqueSp "http://localhost:8002"

### Benchmark: Uploading Synthea Data

The following command uploads synthea data to the gateway.

Syntax:

> java -cp target/perftest.jar Uploader [baseUrl] [directory containing .gz synthea files] [number of threads] [start index]

For example:

> java -cp target/perftest.jar Uploader "http://localhost:8002" /data/synthea 10 0

The optimal number of threads will depend on the size of the cluster. It is worth trying different values to figure out which one gives the best actual performance. Make sure to leave the run going for at least 5 minutes so it can warm up, the numbers output by the loader will be accurate after 5 mins.

Note also the "start index" should probably always be 0. It is provided in case we have any catastrophic failures part way through the actual load so that you can restart at the same index as where the failure stopped. 

### Execute The Benchmark

Syntax:

> java cp target/perftest.jar Benchmarker [gateway base URL] [read node base URL] [megascale DB count] [thread count]

For example:

> java -cp target/perftest.jar Benchmarker "http://localhost:8002" "http://localhost:8001" 2 10

As with the other benchmark, it is worth trying multiple thread counts. Also, make sure to have the right number of megascale DB count

