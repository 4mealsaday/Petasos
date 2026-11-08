#!/usr/bin/env python3
"""Generate Room v1 entities and constraints without changing DB versions/migrations.

Room cannot annotate CHECKs or partial/expression indices. The generated companion
DDL preserves their behavior using INSERT/UPDATE triggers, or nullable unique
indices where the reference CHECK makes them equivalent. Future unfamiliar index
shapes are rejected for explicit review. Use --check in validation workflows.
"""

import argparse
import pathlib
import re
import sqlite3

ROOT = pathlib.Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/com/petasos/app/storage"
NULLABLE_UNIQUE_INDICES = {"provider_event_once", "region_card_once", "story_card_once"}
ACTIVE_UNIQUE_INDICES = {"one_active_trip", "one_active_session", "one_active_segment"}
BANNER = "// Generated from spec/petasos-v0.8.sql by scripts/generate-room-schema.py."


def class_name(table):
    return "".join(part.title() for part in table.split("_")) + "Row"


def quoted(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def array(values):
    return "[" + ", ".join(map(quoted, values)) + "]"


def checks(create_statement):
    """Read balanced CHECK expressions, excluding parentheses in quoted SQL text."""
    position = 0
    while match := re.search(r"\bCHECK\s*\(", create_statement[position:], re.I):
        start = position + match.end()
        depth, index, in_quote = 1, start, False
        while depth:
            char = create_statement[index]
            if char == "'":
                in_quote = not in_quote
            elif not in_quote:
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
            index += 1
        yield create_statement[start:index - 1]
        position = index


def active_unique_triggers(table, key):
    for operation in ("INSERT", "UPDATE"):
        self_row = "OLD" if operation == "UPDATE" else "NEW"
        yield (
            f"CREATE TRIGGER constraint_{table}_one_active_{operation.lower()} "
            f"BEFORE {operation} ON {table} WHEN NEW.status = 'active' AND EXISTS "
            f"(SELECT 1 FROM {table} WHERE status = 'active' AND {key} <> {self_row}.{key}) "
            f"BEGIN SELECT RAISE(ABORT, 'one active {table}'); END"
        )


def generate(connection):
    tables = connection.execute("SELECT name,sql FROM sqlite_master WHERE type='table'").fetchall()
    entities = [BANNER, "// Room metadata plus SchemaConstraints preserves the reference physical constraints.",
                "package com.petasos.app.storage", "", "import androidx.room.ColumnInfo",
                "import androidx.room.Entity", "import androidx.room.ForeignKey", "import androidx.room.Index", "", ""]
    constraints = []
    for table, create_statement in tables:
        columns = connection.execute(f"PRAGMA table_info({table})").fetchall()
        primary = [column[1] for column in sorted(columns, key=lambda column: column[5]) if column[5]]
        indices = []
        for index in connection.execute(f"PRAGMA index_list({table})"):
            if index[3] == "pk":
                continue
            names = [column[2] for column in connection.execute(f"PRAGMA index_info({index[1]})")]
            if index[4]:
                if index[1] in NULLABLE_UNIQUE_INDICES:
                    indices.append((index[1], names, True))
                elif index[1] in ACTIVE_UNIQUE_INDICES:
                    constraints.extend(active_unique_triggers(table, primary[0]))
                else:
                    raise ValueError(f"Unreviewed partial index: {index[1]}")
            else:
                name = index[1] if index[3] != "u" else f"index_{table}_" + "_".join(names)
                indices.append((name, names, bool(index[2])))

        rows = connection.execute(f"PRAGMA foreign_key_list({table})").fetchall()
        foreign_keys = []
        for identifier in sorted({row[0] for row in rows}):
            group = sorted((row for row in rows if row[0] == identifier), key=lambda row: row[1])
            child_columns = [row[3] for row in group]
            foreign_keys.append((group[0][2], child_columns, [row[4] for row in group]))
            has_index = child_columns == primary[:len(child_columns)] or any(
                index[1][:len(child_columns)] == child_columns for index in indices
            )
            if not has_index:
                indices.append((f"index_{table}_" + "_".join(child_columns), child_columns, False))

        arguments = [f"tableName = {quoted(table)}", f"primaryKeys = {array(primary)}"]
        if indices:
            rendered = [f"Index(name = {quoted(name)}, value = {array(names)}, unique = {str(unique).lower()})"
                        for name, names, unique in indices]
            arguments.append("indices = [" + ", ".join(rendered) + "]")
        if foreign_keys:
            rendered = [f"ForeignKey(entity = {class_name(parent)}::class, parentColumns = {array(parent_columns)}, "
                        f"childColumns = {array(child_columns)}, onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION)"
                        for parent, child_columns, parent_columns in foreign_keys]
            arguments.append("foreignKeys = [" + ", ".join(rendered) + "]")
        entities.extend(["@Entity(", "    " + ",\n    ".join(arguments), ")", f"internal data class {class_name(table)}("])
        for _, name, sql_type, not_null, default, _ in columns:
            if default is not None:
                entities.append(f"    @ColumnInfo(defaultValue = {quoted(str(default))})")
            kotlin_type = {"TEXT": "String", "INTEGER": "Long", "REAL": "Double", "BLOB": "ByteArray"}[sql_type]
            entities.append(f"    val {name}: {kotlin_type}{'' if not_null else '?'},")
        entities.extend([") {", f'    override fun toString(): String = "{class_name(table)}([redacted])"', "}", "", ""])
        column_names = {column[1] for column in columns}
        for number, expression in enumerate(checks(create_statement)):
            expression = re.sub(r"'[^']*'|\b[A-Za-z_][A-Za-z_0-9]*\b",
                                lambda match: "NEW." + match[0] if match[0] in column_names else match[0], expression)
            for operation in ("INSERT", "UPDATE"):
                constraints.append(
                    f"CREATE TRIGGER constraint_{table}_check_{number}_{operation.lower()} BEFORE {operation} ON {table} "
                    f"WHEN NOT ({expression}) BEGIN SELECT RAISE(ABORT, 'check {table} {number}'); END"
                )
    constraints.extend(row[0] for row in connection.execute("SELECT sql FROM sqlite_master WHERE type='trigger'"))
    companion = [BANNER, "package com.petasos.app.storage", "", "import androidx.sqlite.db.SupportSQLiteDatabase", "",
                 "/** Version 1 physical constraints not representable by Room Entity annotations. */", "internal object SchemaConstraints {",
                 "    fun installVersion1(db: SupportSQLiteDatabase) {", "        statements.forEach(db::execSQL)", "    }", "",
                 "    val statements: List<String> = listOf("]
    companion.extend('        """' + statement + '""",' for statement in constraints)
    companion.extend(["    )", "}"])
    return {"SchemaRows.kt": "\n".join(entities), "SchemaConstraints.kt": "\n".join(companion) + "\n"}, len(tables), len(constraints)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail instead of rewriting stale generated files")
    arguments = parser.parse_args()
    with sqlite3.connect(":memory:") as connection:
        connection.executescript((ROOT / "spec/petasos-v0.8.sql").read_text())
        files, table_count, constraint_count = generate(connection)
    for name, content in files.items():
        destination = TARGET / name
        if arguments.check:
            if not destination.exists() or destination.read_text() != content:
                raise SystemExit(f"Stale generated Room mapping: {destination}")
        else:
            destination.write_text(content)
    print(f"Room mapping: {table_count} tables, {constraint_count} constraint triggers; {'verified' if arguments.check else 'generated'}")


if __name__ == "__main__":
    main()
