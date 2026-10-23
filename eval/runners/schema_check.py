"""Minimal JSON Schema (draft 2020-12 subset) validator, stdlib only.

Supports exactly the keywords used by eval/schema/case.schema.json:
type (incl. unions), enum, const, required, properties, additionalProperties (bool),
items, minItems, maxItems, uniqueItems, pattern, minimum, maximum, maxLength,
oneOf, anyOf, if/then/else, $ref to local pointers (#/$defs/...).

Returns a list of error strings (empty list means valid). It is intentionally small so the
eval runner has no third-party dependency beyond PyYAML.
"""

from __future__ import annotations

import json
import re
from typing import Any

_TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


class SchemaValidator:
    def __init__(self, schema: dict[str, Any]):
        self.root = schema

    # -- public ------------------------------------------------------------------------------
    def validate(self, instance: Any) -> list[str]:
        errors: list[str] = []
        self._validate(instance, self.root, "$", errors)
        return errors

    # -- internals ---------------------------------------------------------------------------
    def _resolve(self, ref: str) -> dict[str, Any]:
        if not ref.startswith("#/"):
            raise ValueError(f"only local $ref supported, got {ref}")
        node: Any = self.root
        for part in ref[2:].split("/"):
            part = part.replace("~1", "/").replace("~0", "~")
            node = node[part]
        return node

    def _validate(self, inst: Any, schema: Any, path: str, errors: list[str]) -> None:
        if schema is True or schema == {}:
            return
        if schema is False:
            errors.append(f"{path}: schema is false")
            return
        if "$ref" in schema:
            self._validate(inst, self._resolve(schema["$ref"]), path, errors)
            # 2020-12 allows siblings next to $ref; the case schema does not use them.
            return

        if "type" in schema:
            types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
            if not any(_TYPE_CHECKS[t](inst) for t in types):
                errors.append(f"{path}: expected type {types}, got {type(inst).__name__}")
                return

        if "const" in schema and inst != schema["const"]:
            errors.append(f"{path}: expected const {schema['const']!r}, got {inst!r}")
        if "enum" in schema and inst not in schema["enum"]:
            errors.append(f"{path}: {inst!r} not in enum {schema['enum']}")

        if isinstance(inst, str):
            if "pattern" in schema and not re.search(schema["pattern"], inst):
                errors.append(f"{path}: {inst!r} does not match /{schema['pattern']}/")
            if "maxLength" in schema and len(inst) > schema["maxLength"]:
                errors.append(f"{path}: length {len(inst)} > maxLength {schema['maxLength']}")
            if "minLength" in schema and len(inst) < schema["minLength"]:
                errors.append(f"{path}: length {len(inst)} < minLength {schema['minLength']}")

        if isinstance(inst, (int, float)) and not isinstance(inst, bool):
            if "minimum" in schema and inst < schema["minimum"]:
                errors.append(f"{path}: {inst} < minimum {schema['minimum']}")
            if "maximum" in schema and inst > schema["maximum"]:
                errors.append(f"{path}: {inst} > maximum {schema['maximum']}")

        if isinstance(inst, dict):
            for key in schema.get("required", []):
                if key not in inst:
                    errors.append(f"{path}: missing required property {key!r}")
            props = schema.get("properties", {})
            for key, value in inst.items():
                if key in props:
                    self._validate(value, props[key], f"{path}.{key}", errors)
                elif schema.get("additionalProperties") is False:
                    errors.append(f"{path}: unexpected property {key!r}")
                elif isinstance(schema.get("additionalProperties"), dict):
                    self._validate(value, schema["additionalProperties"], f"{path}.{key}", errors)

        if isinstance(inst, list):
            if "minItems" in schema and len(inst) < schema["minItems"]:
                errors.append(f"{path}: {len(inst)} items < minItems {schema['minItems']}")
            if "maxItems" in schema and len(inst) > schema["maxItems"]:
                errors.append(f"{path}: {len(inst)} items > maxItems {schema['maxItems']}")
            if schema.get("uniqueItems"):
                seen = [json.dumps(x, sort_keys=True) for x in inst]
                if len(set(seen)) != len(seen):
                    errors.append(f"{path}: items are not unique")
            if "items" in schema:
                for i, item in enumerate(inst):
                    self._validate(item, schema["items"], f"{path}[{i}]", errors)

        if "anyOf" in schema:
            if not any(not self._errors_of(inst, sub, path) for sub in schema["anyOf"]):
                errors.append(f"{path}: does not match any schema in anyOf")
        if "oneOf" in schema:
            matches = sum(1 for sub in schema["oneOf"] if not self._errors_of(inst, sub, path))
            if matches != 1:
                errors.append(f"{path}: matches {matches} schemas in oneOf (expected exactly 1)")

        if "if" in schema:
            if not self._errors_of(inst, schema["if"], path):
                if "then" in schema:
                    self._validate(inst, schema["then"], path, errors)
            elif "else" in schema:
                self._validate(inst, schema["else"], path, errors)

    def _errors_of(self, inst: Any, schema: Any, path: str) -> list[str]:
        errs: list[str] = []
        self._validate(inst, schema, path, errs)
        return errs


def load_validator(schema_path: str) -> SchemaValidator:
    with open(schema_path, encoding="utf-8") as fh:
        return SchemaValidator(json.load(fh))
