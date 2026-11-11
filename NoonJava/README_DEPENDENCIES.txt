DEPENDENCIES
User-supplied Lucene core/analyzers-common 5.3.0, Commons Lang 2.6,
JSON 20250517, and jsoup 1.23.1 replace the previous Lucene 9 dependencies.
SQLite JDBC 3.45.3.0 and SLF4J 1.7.36 remain. lib/SHA256SUMS.txt records hashes.
Both javac/native-image use lib/*.jar.

DependencyCheck uses Lucene 5 RAMDirectory, a TermQuery and TopDocs.totalHits.
No Lucene 9 queryparser/queries/sandbox JARs are mixed with Lucene 5.3.
The provided set contains no Lucene queryparser JAR; this check does not need it.
Reflection constructors are regenerated from the Lucene 5 SPI service providers.

noon_java_check_dependencies returns 0 on success, or failure bits:
Lucene=1, SQLite=2, JSON=4, jsoup=8, Commons Lang=16.
These checks do not implement new search/index HTTP endpoints.
Rebuild the solution Release x64 / v142 to regenerate the GraalVM DLL.
Windows native-image build and browser execution require testing on Windows.

Verification: supplied JARs preserved byte-for-byte, ZIP integrity checked,
Java 17 compilation and all five dependency smoke checks passed on the JVM.
Windows GraalVM native-image compilation was not performed here.
