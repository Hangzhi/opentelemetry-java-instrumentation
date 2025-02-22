/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.smoketest

import static io.opentelemetry.semconv.resource.attributes.ResourceAttributes.OS_TYPE
import static org.junit.Assume.assumeTrue

import io.opentelemetry.proto.trace.v1.Span
import java.util.jar.Attributes
import java.util.jar.JarFile
import okhttp3.Request
import org.junit.runner.RunWith
import spock.lang.Shared
import spock.lang.Unroll

@RunWith(AppServerTestRunner)
abstract class AppServerTest extends SmokeTest {
  @Shared
  String jdk
  @Shared
  String serverVersion
  @Shared
  boolean isWindows

  def setupSpec() {
    def appServer = AppServerTestRunner.currentAppServer(this.getClass())
    serverVersion = appServer.version()
    jdk = appServer.jdk()

    isWindows = System.getProperty("os.name").toLowerCase().contains("windows")
    startTarget(jdk, serverVersion, isWindows)
  }

  @Override
  protected String getTargetImage(String jdk) {
    throw new UnsupportedOperationException("App servers tests should use getTargetImagePrefix")
  }

  @Override
  protected String getTargetImage(String jdk, String serverVersion, boolean windows) {
    String platformSuffix = windows ? "-windows" : ""
    String extraTag = "20210316.657616194"
    String fullSuffix = "-${serverVersion}-jdk$jdk$platformSuffix-$extraTag"
    return getTargetImagePrefix() + fullSuffix
  }

  protected abstract String getTargetImagePrefix()

  def cleanupSpec() {
    stopTarget()
  }

  boolean testSmoke() {
    true
  }

  boolean testAsyncSmoke() {
    true
  }

  boolean testException() {
    true
  }

  boolean testRequestWebInfWebXml() {
    true
  }

  //TODO add assert that server spans were created by servers, not by servlets
  @Unroll
  def "#appServer smoke test on JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    assumeTrue(testSmoke())

    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/greeting"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds
    String responseBody = response.body().string()

    then: "There is one trace"
    traceIds.size() == 1

    and: "trace id is present in the HTTP headers as reported by the called endpoint"
    responseBody.contains(traceIds.find())

    and: "Server spans in the distributed trace"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 2

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/greeting')) == 1
    traces.countSpansByName(getSpanName('/app/headers')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Client and server spans for the remote call"
    traces.countFilteredAttributes("http.url", "http://localhost:8080/app/headers") == 2

    and: "Number of spans with http protocol version"
    traces.countFilteredAttributes("http.flavor", "1.1") == 3

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == 3

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == 3

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer test static file found on JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/hello.txt"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds
    String responseBody = response.body().string()

    then: "There is one trace"
    traceIds.size() == 1

    and: "Response contains Hello"
    responseBody.contains("Hello")

    and: "There is one server span"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 1

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/hello.txt')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == 1

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == 1

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer test static file not found on JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/file-that-does-not-exist"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds

    then: "There is one trace"
    traceIds.size() == 1

    and: "Response code is 404"
    response.code() == 404

    and: "There is one server span"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 1

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/file-that-does-not-exist')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == traces.countSpans()

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == traces.countSpans()

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer test request for WEB-INF/web.xml on JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    assumeTrue(testRequestWebInfWebXml())

    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/WEB-INF/web.xml"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds

    then: "There is one trace"
    traceIds.size() == 1

    and: "Response code is 404"
    response.code() == 404

    and: "There is one server span"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 1

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/WEB-INF/web.xml')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Number of spans with http protocol version"
    traces.countFilteredAttributes("http.flavor", "1.1") == 1

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == traces.countSpans()

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == traces.countSpans()

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer test request with error JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    assumeTrue(testException())

    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/exception"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds

    then: "There is one trace"
    traceIds.size() == 1

    and: "Response code is 500"
    response.code() == 500

    and: "There is one server span"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 1

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/exception')) == 1

    and: "There is one exception"
    traces.countFilteredEventAttributes('exception.message', 'This is expected') == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == 1

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == 1

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer test request outside deployed application JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/this-is-definitely-not-there-but-there-should-be-a-trace-nevertheless"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds

    then: "There is one trace"
    traceIds.size() == 1

    and: "Response code is 404"
    response.code() == 404

    and: "There is one server span"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 1

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/this-is-definitely-not-there-but-there-should-be-a-trace-nevertheless')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Number of spans with http protocol version"
    traces.countFilteredAttributes("http.flavor", "1.1") == 1

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == traces.countSpans()

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == traces.countSpans()

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  @Unroll
  def "#appServer async smoke test on JDK #jdk"(String appServer, String jdk, boolean isWindows) {
    assumeTrue(testAsyncSmoke())

    String url = "http://localhost:${containerManager.getTargetMappedPort(8080)}/app/asyncgreeting"
    def request = new Request.Builder().url(url).get().build()
    def currentAgentVersion = new JarFile(agentPath).getManifest().getMainAttributes().get(Attributes.Name.IMPLEMENTATION_VERSION)

    when:
    def response = CLIENT.newCall(request).execute()
    TraceInspector traces = new TraceInspector(waitForTraces())
    Set<String> traceIds = traces.traceIds
    String responseBody = response.body().string()

    then: "There is one trace"
    traceIds.size() == 1

    and: "trace id is present in the HTTP headers as reported by the called endpoint"
    responseBody.contains(traceIds.find())

    and: "Server spans in the distributed trace"
    traces.countSpansByKind(Span.SpanKind.SPAN_KIND_SERVER) == 2

    and: "Expected span names"
    traces.countSpansByName(getSpanName('/app/asyncgreeting')) == 1
    traces.countSpansByName(getSpanName('/app/headers')) == 1

    and: "The span for the initial web request"
    traces.countFilteredAttributes("http.url", url) == 1

    and: "Client and server spans for the remote call"
    traces.countFilteredAttributes("http.url", "http://localhost:8080/app/headers") == 2

    and: "Number of spans with http protocol version"
    traces.countFilteredAttributes("http.flavor", "1.1") == 3

    and: "Number of spans tagged with current otel library version"
    traces.countFilteredResourceAttributes("telemetry.auto.version", currentAgentVersion) == 3

    and: "Number of spans tagged with expected OS type"
    traces.countFilteredResourceAttributes(OS_TYPE.key, isWindows ? "WINDOWS" : "LINUX") == 3

    cleanup:
    response?.close()

    where:
    [appServer, jdk, isWindows] << getTestParams()
  }

  protected String getSpanName(String path) {
    switch (path) {
      case "/app/greeting":
      case "/app/headers":
      case "/app/exception":
      case "/app/asyncgreeting":
        return path
      case "/app/hello.txt":
      case "/app/file-that-does-not-exist":
        return "/app/*"
    }
    return "HTTP GET"
  }

  protected List<List<Object>> getTestParams() {
    return [
      [serverVersion, jdk, isWindows]
    ]
  }
}