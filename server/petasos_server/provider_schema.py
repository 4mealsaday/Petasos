"""Provider-only schema projection; the service validates the unchanged original.

Claude's documented SDK approach moves unsupported constraints into descriptions
and validates the result against the original schema afterward:
https://platform.claude.com/docs/en/build-with-claude/structured-outputs
https://docs.aws.amazon.com/bedrock/latest/userguide/structured-output.html
"""
import copy
import json

from .contracts import Failure


# Exact reviewed model identities, not a family-wide assumption about future models.
CLAUDE_SCHEMA_MODELS = frozenset({
    "anthropic/claude-sonnet-4.6",
    "anthropic/claude-4.6-sonnet-20260217",
})
DEFERRED_CONSTRAINTS = frozenset({
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "minLength", "maxLength", "maxItems", "uniqueItems",
})


def provider_schema(model, schema):
    """Adapt only the reviewed Claude candidate without weakening wire validation."""
    result = copy.deepcopy(schema)
    if model not in CLAUDE_SCHEMA_MODELS:
        return result

    def visit(node):
        deferred = {}
        for keyword in sorted(DEFERRED_CONSTRAINTS):
            if keyword in node:
                deferred[keyword] = node.pop(keyword)
        if "minItems" in node and node["minItems"] not in (0, 1):
            deferred["minItems"] = node.pop("minItems")
        if deferred:
            guidance = "Must also satisfy: " + json.dumps(deferred, ensure_ascii=False, sort_keys=True)
            node["description"] = (node.get("description", "") + " " + guidance).strip()

        if "oneOf" in node:
            branches = node["oneOf"]
            # Our nullable fields have disjoint string/null branches. For those
            # fields anyOf is equivalent; never relax an overlapping oneOf.
            if (not isinstance(branches, list) or len(branches) != 2
                or any(not isinstance(branch, dict) for branch in branches)
                or sorted(branch.get("type", "") for branch in branches) != ["null", "string"]
                or "anyOf" in node):
                raise Failure("UPSTREAM_UNAVAILABLE", 503, False)
            node["anyOf"] = node.pop("oneOf")

        # Visit schema positions, not property names or annotation values.
        for keyword in ("properties", "$defs", "definitions", "patternProperties", "dependentSchemas"):
            for child in node.get(keyword, {}).values():
                if isinstance(child, dict):
                    visit(child)
        for keyword in ("items", "additionalProperties", "contains", "propertyNames", "not", "if", "then", "else"):
            if isinstance(node.get(keyword), dict):
                visit(node[keyword])
        for keyword in ("anyOf", "allOf", "prefixItems"):
            for child in node.get(keyword, []):
                if isinstance(child, dict):
                    visit(child)

    visit(result)
    return result
