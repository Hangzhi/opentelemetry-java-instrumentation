/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.lettuce.v5_1

import static io.opentelemetry.api.trace.SpanKind.CLIENT

import io.lettuce.core.RedisClient
import io.opentelemetry.instrumentation.test.InstrumentationSpecification
import io.opentelemetry.instrumentation.test.utils.PortUtils
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes
import redis.embedded.RedisServer
import spock.lang.Shared

abstract class AbstractLettuceSyncClientAuthTest extends InstrumentationSpecification {
  public static final String HOST = "127.0.0.1"
  public static final int DB_INDEX = 0

  abstract RedisClient createClient(String uri)

  @Shared
  int port
  @Shared
  String password
  @Shared
  String dbAddr
  @Shared
  String embeddedDbUri

  @Shared
  RedisServer redisServer

  RedisClient redisClient

  def setupSpec() {
    port = PortUtils.findOpenPort()
    dbAddr = HOST + ":" + port + "/" + DB_INDEX
    embeddedDbUri = "redis://" + dbAddr
    password = "password"

    redisServer = RedisServer.builder()
    // bind to localhost to avoid firewall popup
      .setting("bind " + HOST)
    // set max memory to avoid problems in CI
      .setting("maxmemory 128M")
    // Set password
      .setting("requirepass " + password)
      .port(port).build()
  }

  def setup() {
    redisClient = createClient(embeddedDbUri)
    redisClient.setOptions(LettuceTestUtil.CLIENT_OPTIONS)
    redisServer.start()
  }

  def cleanup() {
    redisServer.stop()
  }

  def "auth command"() {
    setup:
    def res = redisClient.connect().sync().auth(password)

    expect:
    res == "OK"
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
          name "AUTH"
          kind CLIENT
          errored false
          attributes {
            "${SemanticAttributes.NET_TRANSPORT.key}" "IP.TCP"
            "${SemanticAttributes.NET_PEER_IP.key}" "127.0.0.1"
            "${SemanticAttributes.NET_PEER_PORT.key}" port
            "${SemanticAttributes.DB_CONNECTION_STRING.key}" "redis://127.0.0.1:$port"
            "${SemanticAttributes.DB_SYSTEM.key}" "redis"
            "${SemanticAttributes.DB_STATEMENT.key}" "AUTH ?"
          }
          event(0) {
            eventName "redis.encode.start"
          }
          event(1) {
            eventName "redis.encode.end"
          }
        }
      }
    }
  }
}
