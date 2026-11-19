package io.github.samsanjaysatish.macvagdiag.mcp;

import io.github.samsanjaysatish.macvagdiag.scan.ModuleAddress;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * JSON Schemas (2020-12) for the tools: strict inputs ({@code additionalProperties: false}) and an output schema per
 * tool matching {@link ToolOutput}. Output objects are closed too, so a test catches any drift between the two (the
 * SDK validates every result against its output schema). Each output schema carries the shared {@code $defs}.
 */
final class ToolSchemas {

    private ToolSchemas() {
    }

    /** No arguments at all. */
    static Map<String, Object> noArguments() {
        return parse("""
                {"type": "object", "properties": {}, "additionalProperties": false}
                """);
    }

    /** {@code {"module": "08"}}: exactly one known module address. */
    static Map<String, Object> moduleArgument() {
        String known = ModuleAddress.known().stream().map(m -> "\"" + m.hex() + "\"").collect(Collectors.joining(", "));
        String names = ModuleAddress.known().stream().map(m -> m.hex() + " " + m.name()).collect(Collectors.joining(", "));
        return parse("""
                {
                  "type": "object",
                  "properties": {
                    "module": {
                      "type": "string",
                      "description": "VW module address as VCDS writes it, exactly two hex digits. Known modules: %s.",
                      "enum": [%s]
                    }
                  },
                  "required": ["module"],
                  "additionalProperties": false
                }
                """.formatted(names, known));
    }

    /** {@code {"module": "01", "groups": [1, 11], "query": "boost"}}; groups and query optional. */
    static Map<String, Object> availableDataArguments() {
        Map<String, Object> schema = new LinkedHashMap<>(moduleArgument());
        @SuppressWarnings("unchecked") // built just above from our own JSON: properties is an object
        Map<String, Object> props = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        props.putAll(parse("""
                {
                  "groups": {
                    "type": "array", "minItems": 1, "maxItems": 20,
                    "items": {"type": "integer", "minimum": 1, "maximum": 255},
                    "description": "Measuring-block group numbers whose fields to list, e.g. [1, 11] (VCDS's 001, 011)."
                  },
                  "query": {
                    "type": "string", "minLength": 1, "maxLength": 40,
                    "description": "Case-insensitive text to find in group titles and field names, e.g. 'temperature'."
                  }
                }
                """));
        schema.put("properties", props);
        return schema;
    }

    private static final String VALUE_IDS = """
            "values": {
              "type": "array", "minItems": 1, "maxItems": 16,
              "items": {"type": "string", "pattern": "^[0-9A-Fa-f]{2}:[0-9]{3}\\\\.[1-4]$"},
              "description": "Value IDs from list_available_data, all from one module and at most 4 groups, e.g. ['01:001.1', '01:011.3']."
            }""";

    static Map<String, Object> readLiveDataArguments() {
        return parse("""
                {"type": "object", "properties": {%s}, "required": ["values"], "additionalProperties": false}
                """.formatted(VALUE_IDS));
    }

    static Map<String, Object> recordLiveDataArguments() {
        return parse("""
                {
                  "type": "object",
                  "properties": {
                    %s,
                    "seconds": {"type": "integer", "minimum": 1, "maximum": 120, "description": "How long to record."}
                  },
                  "required": ["values", "seconds"],
                  "additionalProperties": false
                }
                """.formatted(VALUE_IDS));
    }

    private static final String LIMIT_HIT = """
            {
              "anyOf": [
                {
                  "type": "object",
                  "properties": {
                    "limit": {"type": "string"},
                    "description": {"type": "string"},
                    "retryAfterSeconds": {"type": "integer", "minimum": 0},
                    "message": {"type": "string"}
                  },
                  "required": ["limit", "description", "retryAfterSeconds", "message"],
                  "additionalProperties": false
                },
                {"type": "null"}
              ]
            }""";

    static Map<String, Object> availableDataOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "module": {"$ref": "#/$defs/module"},
                    "source": {"enum": ["LABELS", "PROBED", "PROBE_INCOMPLETE", "NOT_SUPPORTED", "NOT_RESPONDING"]},
                    "partNumber": {"type": ["string", "null"]},
                    "labelSource": {"type": ["string", "null"]},
                    "labelTrail": {"$ref": "#/$defs/strings"},
                    "probedThrough": {"type": "integer", "minimum": 0, "maximum": 255},
                    "withheldGroups": {"type": "array", "items": {"type": "integer"}},
                    "filter": {"type": ["string", "null"]},
                    "groupCount": {"type": "integer", "minimum": 0},
                    "fieldCount": {"type": "integer", "minimum": 0},
                    "groups": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "group": {"type": "integer", "minimum": 1, "maximum": 255},
                          "title": {"type": ["string", "null"]},
                          "fieldCount": {"type": "integer", "minimum": 0},
                          "fields": {
                            "anyOf": [
                              {
                                "type": "array",
                                "items": {
                                  "type": "object",
                                  "properties": {
                                    "id": {"type": "string"},
                                    "name": {"type": ["string", "null"]},
                                    "quantity": {"type": ["string", "null"]},
                                    "unit": {"type": ["string", "null"]},
                                    "specs": {"$ref": "#/$defs/strings"},
                                    "timestamp": {"$ref": "#/$defs/timestamp"}
                                  },
                                  "required": ["id", "name", "quantity", "unit", "specs", "timestamp"],
                                  "additionalProperties": false
                                }
                              },
                              {"type": "null"}
                            ]
                          }
                        },
                        "required": ["group", "title", "fieldCount", "fields"],
                        "additionalProperties": false
                      }
                    },
                    "truncated": {"type": "boolean"},
                    "notes": {"$ref": "#/$defs/strings"},
                    "limitHit": %s,
                    "requests": {"type": "integer", "minimum": 0},
                    "timestamp": {"$ref": "#/$defs/timestamp"},
                    "hint": {"type": "string"}
                  },
                  "required": ["demo", "module", "source", "partNumber", "labelSource", "labelTrail", "probedThrough",
                               "withheldGroups", "filter", "groupCount", "fieldCount", "groups", "truncated", "notes",
                               "limitHit", "requests", "timestamp", "hint"],
                  "additionalProperties": false
                }
                """.formatted(LIMIT_HIT));
    }

    static Map<String, Object> liveDataOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "module": {"$ref": "#/$defs/module"},
                    "status": {"enum": ["OK", "PARTIAL", "NOT_RESPONDING", "CATALOG_CHANGED", "ERROR"]},
                    "labelSource": {"type": ["string", "null"]},
                    "values": {"type": "array", "items": {"$ref": "#/$defs/liveValue"}},
                    "notes": {"$ref": "#/$defs/strings"},
                    "limitHit": %s,
                    "requests": {"type": "integer", "minimum": 0},
                    "startedAt": {"$ref": "#/$defs/timestamp"},
                    "timestamp": {"$ref": "#/$defs/timestamp"}
                  },
                  "required": ["demo", "module", "status", "labelSource", "values", "notes", "limitHit", "requests",
                               "startedAt", "timestamp"],
                  "additionalProperties": false
                }
                """.formatted(LIMIT_HIT));
    }

    static Map<String, Object> recordingOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "module": {"$ref": "#/$defs/module"},
                    "recordingId": {"type": "string"},
                    "status": {"enum": ["OK", "PARTIAL", "NOT_RESPONDING", "CATALOG_CHANGED", "ERROR"]},
                    "labelSource": {"type": ["string", "null"]},
                    "rounds": {"type": "integer", "minimum": 0},
                    "requestedSeconds": {"type": "integer", "minimum": 0},
                    "recordedSeconds": {"type": "number", "minimum": 0},
                    "file": {"type": ["string", "null"]},
                    "series": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": {"type": "string"},
                          "name": {"type": ["string", "null"]},
                          "quantity": {"type": ["string", "null"]},
                          "unit": {"type": ["string", "null"]},
                          "specs": {"$ref": "#/$defs/strings"},
                          "samples": {"type": "integer", "minimum": 0},
                          "decoded": {"type": "integer", "minimum": 0},
                          "min": {"type": ["number", "null"]},
                          "max": {"type": ["number", "null"]},
                          "average": {"type": ["number", "null"]},
                          "first": {"type": ["number", "null"]},
                          "last": {"type": ["number", "null"]},
                          "minAtSeconds": {"type": ["number", "null"]},
                          "maxAtSeconds": {"type": ["number", "null"]},
                          "events": {"$ref": "#/$defs/strings"},
                          "timestamp": {"$ref": "#/$defs/timestamp"}
                        },
                        "required": ["id", "name", "quantity", "unit", "specs", "samples", "decoded", "min", "max",
                                     "average", "first", "last", "minAtSeconds", "maxAtSeconds", "events", "timestamp"],
                        "additionalProperties": false
                      }
                    },
                    "notes": {"$ref": "#/$defs/strings"},
                    "limitHit": %s,
                    "requests": {"type": "integer", "minimum": 0},
                    "startedAt": {"$ref": "#/$defs/timestamp"},
                    "timestamp": {"$ref": "#/$defs/timestamp"}
                  },
                  "required": ["demo", "module", "recordingId", "status", "labelSource", "rounds", "requestedSeconds",
                               "recordedSeconds", "file", "series", "notes", "limitHit", "requests", "startedAt",
                               "timestamp"],
                  "additionalProperties": false
                }
                """.formatted(LIMIT_HIT));
    }

    static Map<String, Object> sessionStatusOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "adapter": {"$ref": "#/$defs/state"},
                    "ignition": {"$ref": "#/$defs/state"},
                    "engine": {"$ref": "#/$defs/state"},
                    "demo": {"type": "boolean"},
                    "timestamp": {"$ref": "#/$defs/timestamp"},
                    "hint": {"type": "string"}
                  },
                  "required": ["adapter", "ignition", "engine", "demo", "timestamp", "hint"],
                  "additionalProperties": false
                }
                """);
    }

    static Map<String, Object> moduleListOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "modules": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "address": {"$ref": "#/$defs/address"},
                          "name": {"type": "string"},
                          "reachability": {"enum": ["RESPONDING", "NOT_RESPONDING", "NOT_CHECKED", "NOT_INSTALLED"]},
                          "protocol": {"$ref": "#/$defs/protocolOrNull"},
                          "link": {"type": ["string", "null"]},
                          "gatewayStatus": {"type": ["string", "null"]},
                          "message": {"type": "string"},
                          "timestamp": {"$ref": "#/$defs/timestamp"}
                        },
                        "required": ["address", "name", "reachability", "protocol", "link", "gatewayStatus", "message",
                                     "timestamp"],
                        "additionalProperties": false
                      }
                    },
                    "counts": {
                      "type": "object",
                      "properties": {
                        "responding": {"type": "integer", "minimum": 0},
                        "notResponding": {"type": "integer", "minimum": 0},
                        "notChecked": {"type": "integer", "minimum": 0},
                        "notInstalled": {"type": "integer", "minimum": 0}
                      },
                      "required": ["responding", "notResponding", "notChecked", "notInstalled"],
                      "additionalProperties": false
                    },
                    "note": {"type": "string"}
                  },
                  "required": ["demo", "modules", "counts", "note"],
                  "additionalProperties": false
                }
                """);
    }

    static Map<String, Object> faultCodesOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "module": {"$ref": "#/$defs/module"},
                    "status": {"enum": ["OK", "FAULTS", "NOT_RESPONDING", "ERROR", "INCOMPLETE", "SKIPPED",
                                        "GATEWAY_STATUS_ONLY"]},
                    "statusDescription": {"type": "string"},
                    "protocol": {"$ref": "#/$defs/protocolOrNull"},
                    "link": {"type": ["string", "null"]},
                    "faultCount": {"type": "integer", "minimum": 0},
                    "faultListComplete": {"type": "boolean"},
                    "faults": {"type": "array", "items": {"$ref": "#/$defs/fault"}},
                    "identification": {"anyOf": [{"$ref": "#/$defs/identification"}, {"type": "null"}]},
                    "notes": {"$ref": "#/$defs/strings"},
                    "limitHit": {
                      "anyOf": [
                        {
                          "type": "object",
                          "properties": {
                            "limit": {"type": "string"},
                            "description": {"type": "string"},
                            "retryAfterSeconds": {"type": "integer", "minimum": 0},
                            "message": {"type": "string"}
                          },
                          "required": ["limit", "description", "retryAfterSeconds", "message"],
                          "additionalProperties": false
                        },
                        {"type": "null"}
                      ]
                    },
                    "requests": {"type": "integer", "minimum": 0},
                    "startedAt": {"$ref": "#/$defs/timestamp"},
                    "timestamp": {"$ref": "#/$defs/timestamp"}
                  },
                  "required": ["demo", "module", "status", "statusDescription", "protocol", "link",
                               "faultCount", "faultListComplete", "faults", "identification", "notes", "limitHit",
                               "requests", "startedAt", "timestamp"],
                  "additionalProperties": false
                }
                """);
    }

    static Map<String, Object> vehicleInfoOutput() {
        return output("""
                {
                  "type": "object",
                  "properties": {
                    "demo": {"type": "boolean"},
                    "mileage": {"$ref": "#/$defs/value"},
                    "modules": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "address": {"$ref": "#/$defs/address"},
                          "name": {"type": "string"},
                          "reachability": {"enum": ["RESPONDING", "NOT_RESPONDING", "NOT_CHECKED", "NOT_INSTALLED"]},
                          "protocol": {"$ref": "#/$defs/protocolOrNull"},
                          "link": {"type": ["string", "null"]},
                          "identification": {"anyOf": [{"$ref": "#/$defs/identification"}, {"type": "null"}]},
                          "notes": {"$ref": "#/$defs/strings"},
                          "timestamp": {"$ref": "#/$defs/timestamp"}
                        },
                        "required": ["address", "name", "reachability", "protocol", "link", "identification", "notes",
                                     "timestamp"],
                        "additionalProperties": false
                      }
                    },
                    "timestamp": {"$ref": "#/$defs/timestamp"}
                  },
                  "required": ["demo", "mileage", "modules", "timestamp"],
                  "additionalProperties": false
                }
                """);
    }

    /** Shared shapes. Every value read from the car has an id, a unit (null = unknown / none) and a timestamp. */
    private static final String DEFS = """
            {
              "timestamp": {"type": "string", "format": "date-time"},
              "address": {"type": "string", "pattern": "^[0-9A-F]{2}$"},
              "strings": {"type": "array", "items": {"type": "string"}},
              "protocolOrNull": {"enum": ["KWP2000", "UDS", null]},
              "module": {
                "type": "object",
                "properties": {"address": {"$ref": "#/$defs/address"}, "name": {"type": "string"}},
                "required": ["address", "name"],
                "additionalProperties": false
              },
              "value": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "value": {"type": ["string", "integer", "boolean", "null"]},
                  "unit": {"type": ["string", "null"]},
                  "timestamp": {"$ref": "#/$defs/timestamp"},
                  "detail": {"type": ["string", "null"]},
                  "source": {"type": ["string", "null"]}
                },
                "required": ["id", "value", "unit", "timestamp"],
                "additionalProperties": false
              },
              "state": {
                "allOf": [{"$ref": "#/$defs/value"}],
                "required": ["detail"],
                "properties": {"value": {"type": "string"}}
              },
              "byte": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "raw": {"type": "string", "pattern": "^[0-9A-F]{2}$"},
                  "value": {"type": "integer", "minimum": 0, "maximum": 255},
                  "unit": {"type": ["string", "null"]},
                  "timestamp": {"$ref": "#/$defs/timestamp"}
                },
                "required": ["id", "raw", "value", "unit", "timestamp"],
                "additionalProperties": false
              },
              "raw": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "raw": {"type": "string"},
                  "text": {"type": ["string", "null"]},
                  "unit": {"type": ["string", "null"]},
                  "timestamp": {"$ref": "#/$defs/timestamp"}
                },
                "required": ["id", "raw", "text", "unit", "timestamp"],
                "additionalProperties": false
              },
              "identification": {
                "type": "object",
                "properties": {
                  "protocol": {"enum": ["KWP2000", "UDS"]},
                  "values": {"type": "array", "items": {"$ref": "#/$defs/value"}},
                  "raw": {"type": "array", "items": {"$ref": "#/$defs/raw"}},
                  "notes": {"$ref": "#/$defs/strings"}
                },
                "required": ["protocol", "values", "raw", "notes"],
                "additionalProperties": false
              },
              "freezeFrame": {
                "type": "object",
                "properties": {
                  "availability": {"enum": ["READ", "NOT_AVAILABLE", "FAILED", "SKIPPED"]},
                  "message": {"type": "string"},
                  "records": {"type": "array", "items": {"$ref": "#/$defs/raw"}},
                  "fields": {"type": "array", "items": {"$ref": "#/$defs/value"}},
                  "responses": {"$ref": "#/$defs/strings"},
                  "notes": {"$ref": "#/$defs/strings"}
                },
                "required": ["availability", "message", "records", "fields", "responses", "notes"],
                "additionalProperties": false
              },
              "liveValue": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "name": {"type": ["string", "null"]},
                  "quantity": {"type": ["string", "null"]},
                  "value": {"type": ["number", "null"]},
                  "text": {"type": "string"},
                  "unit": {"type": ["string", "null"]},
                  "raw": {"type": ["string", "null"]},
                  "state": {"enum": ["OK", "EMPTY", "NOT_DECODED", "NOT_READ", "WITHHELD"]},
                  "problem": {"type": ["string", "null"]},
                  "specs": {"$ref": "#/$defs/strings"},
                  "timestamp": {"$ref": "#/$defs/timestamp"}
                },
                "required": ["id", "name", "quantity", "value", "text", "unit", "raw", "state", "problem", "specs",
                             "timestamp"],
                "additionalProperties": false
              },
              "fault": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "code": {"type": "string"},
                  "protocol": {"enum": ["KWP2000", "UDS"]},
                  "record": {"type": "string"},
                  "status": {"$ref": "#/$defs/byte"},
                  "statusText": {"type": "string"},
                  "statusFlags": {"$ref": "#/$defs/strings"},
                  "failureType": {"anyOf": [{"$ref": "#/$defs/byte"}, {"type": "null"}]},
                  "obdCode": {"type": ["string", "null"]},
                  "freezeFrame": {"$ref": "#/$defs/freezeFrame"},
                  "unit": {"type": ["string", "null"]},
                  "timestamp": {"$ref": "#/$defs/timestamp"}
                },
                "required": ["id", "code", "protocol", "record", "status", "statusText", "statusFlags", "failureType",
                             "obdCode", "freezeFrame", "unit", "timestamp"],
                "additionalProperties": false
              }
            }
            """;

    private static Map<String, Object> output(String json) {
        Map<String, Object> schema = new LinkedHashMap<>(parse(json));
        schema.put("$defs", parse(DEFS));
        return schema;
    }

    private static Map<String, Object> parse(String json) {
        try {
            return McpJsonDefaults.getMapper().readValue(json, new TypeRef<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("bad built-in schema", e);
        }
    }
}
