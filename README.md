# Setting Up the Environment

The following is a list of files in this repository that need to be configured in the real environment:

* smilecdr-write/classes/cdr-config-Write.properties - Config for the write node
* smilecdr-write/classes/config_seeding/fhir-partitions.json - Partition seeding file (edit this if we need more than 4 megascale DBs)
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

# Configuring MegaScale

The `BenchmarkMegaScaleConnectionProvidingInterceptor` handles providing MegaScale connections. It uses environment variables to supply them. The following example shows how to set these within the `setenv` file, but this can also be done by passing the same properties in as Docker Env variables.

```bash
# How many megascale DBa are there
JVMARGS="$JVMARGS -DMEGASCALE_COUNT=2"

# Coordinates of MegaScale DB 1 (note that these are 1-indexed, not 0-indexed!)
JVMARGS="$JVMARGS -DMEGASCALE_URL_1=jdbc:postgresql://localhost:5432/cdr_ms1"
JVMARGS="$JVMARGS -DMEGASCALE_USER_1=cdr_ms1"
JVMARGS="$JVMARGS -DMEGASCALE_PASS_1=cdr"

# Coordinates of MegaScale DB 2
JVMARGS="$JVMARGS -DMEGASCALE_URL_2=jdbc:postgresql://localhost:5432/cdr_ms2"
JVMARGS="$JVMARGS -DMEGASCALE_USER_2=cdr_ms2"
JVMARGS="$JVMARGS -DMEGASCALE_PASS_2=cdr"
```

If you are using environment variables, use the same names and make sure to omit the "D". So for example:

```properties
MEGASCALE_COUNT=2

# Coordinates of MegaScale DB 1 (note that these are 1-indexed, not 0-indexed!)
MEGASCALE_URL_1=jdbc:postgresql://localhost:5432/cdr_ms1
MEGASCALE_USER_1=cdr_ms1
MEGASCALE_PASS_1=cdr

...etc…
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

