#!/usr/bin/env python3
"""Builds docs/data-dictionary.md and docs/diagrams/erd-backend.md from the real database structure.

    psql -U pari -d pari -At -f docs/tools/schema.sql > schema.json     # database migrated by the backend
    python docs/tools/schema_dictionary.py schema.json docs/data-dictionary.md docs/diagrams/erd-backend.md

Only the standard library is used. Descriptions of tables and enum meanings are kept in TABLE_PURPOSE below, because the
database cannot tell them; a table without a description is reported on stderr so the text is not forgotten.
"""
import json
import re
import sys
from pathlib import Path

TABLE_PURPOSE = {
    "users": "Every account. A person can hold several roles (see user_roles); active_role is the one used by the UI. The phone number is stored encrypted (phone_enc) and found through phone_hash.",
    "user_roles": "Roles of a user: DRIVER, OWNER, VERIFIER, SUPPORT, ADMIN.",
    "auth_tokens": "One-time and refresh tokens (refresh, e-mail verification, phone code, password reset). Table and entity exist, no endpoint uses them yet.",
    "vehicles": "Cars of a driver; a reservation points at one of them.",
    "payment_methods": "Tokenised cards of a user (provider reference, brand, last four digits). No card numbers are stored.",
    "parking_spots": "A parking space offered by an owner: address, coordinates (PostGIS location is generated from lat/lng), size, hourly price, status, occupancy, cached rating.",
    "spot_photos": "Photos of a space; storage_key points to a file in the media directory.",
    "amenities": "Reference list of features a space can have (covered, EV charger, gate, ...).",
    "spot_amenities": "Many-to-many link between spaces and amenities.",
    "availability_rules": "Weekly opening hours of a space (ISO day of week, local start and end time, optional validity dates).",
    "availability_exceptions": "One-off periods: BLOCKED (not bookable) or OPEN (extra opening).",
    "reservations": "A booking of a space for a time range, with the price snapshot, access codes and the session times. The exclusion constraint reservations_no_overlap forbids two slot-holding reservations overlapping.",
    "payments": "Money movements of a reservation with the payment provider: CHARGE, PENALTY, REFUND, each PENDING, SUCCEEDED or FAILED.",
    "payouts": "The owner's settlement for one reservation (soft escrow): gross, commission, net, released after release_at.",
    "withdrawals": "An owner's withdrawal of released money to a bank account (IBAN stored encrypted).",
    "reviews": "A driver's rating of a space after a COMPLETED reservation; one per reservation and author.",
    "reports": "Complaints about a reservation. Table and entity exist, no endpoint uses them yet.",
    "report_messages": "Messages in a report thread. Not used by an endpoint yet.",
    "sanctions": "Warnings and blocks issued to a user. Not used by an endpoint yet.",
    "verification_requests": "A request to inspect a space (F10). Created when the owner asks for it while adding a space; no endpoint handles the inspection yet.",
    "notifications": "In-app notifications for a user, created from reservation events.",
    "premium_subscriptions": "Owner Premium plans (0% commission, priority in search).",
    "audit_log": "Append-only record of financial and reservation operations (a trigger rejects update, delete and truncate).",
    "platform_settings": "Tunable business values (commission, fees, timeouts) read through PlatformSettings; some are placeholders.",
}

ABBREV = {"character varying": "varchar", "timestamp with time zone": "timestamptz", "double precision": "double",
          "boolean": "boolean", "integer": "integer", "bigint": "bigint", "uuid": "uuid", "text": "text", "jsonb": "jsonb",
          "numeric": "numeric", "time without time zone": "time", "date": "date", "USER-DEFINED": "geography"}


def col_type(c):
    t = ABBREV.get(c["data_type"], c["data_type"])
    if c["data_type"] == "character varying" and c["len"]:
        return f"varchar({c['len']})"
    if c["data_type"] == "numeric" and c["prec"]:
        return f"numeric({c['prec']},{c['scale']})"
    return t


def main(src, doc_path, erd_path):
    d = json.loads(Path(src).read_text(encoding="utf-8"))
    by_table = {}
    for c in d["columns"]:
        by_table.setdefault(c["table_name"], []).append(c)
    cons = {}
    for k in d["constraints"]:
        cons.setdefault(k["t"], []).append(k)
    idx = {}
    for i in d["indexes"] or []:
        idx.setdefault(i["tablename"], []).append(i)

    pk, fk, uk = {}, {}, set()
    fk_edges = []
    for table, items in cons.items():
        for k in items:
            cols = re.findall(r"\(([^)]*)\)", k["def"])
            if k["ty"] == "p":
                pk[table] = [x.strip() for x in cols[0].split(",")]
            elif k["ty"] == "f":
                m = re.match(r"FOREIGN KEY \((\w+)\) REFERENCES (\w+)\((\w+)\)", k["def"])
                if m:
                    fk[(table, m.group(1))] = f"{m.group(2)}.{m.group(3)}"
                    fk_edges.append((m.group(2), table, m.group(1)))
            elif k["ty"] == "u" and len(cols) == 1 and "," not in cols[0]:
                uk.add((table, cols[0].strip()))

    tables = sorted(by_table)
    missing = [t for t in tables if t not in TABLE_PURPOSE]
    if missing:
        print("WARNING: no description for " + ", ".join(missing), file=sys.stderr)

    out = ["# Data dictionary", "",
           "<!-- Generated by docs/tools/schema_dictionary.py from the migrated database. Do not edit by hand; "
           "change the migrations or TABLE_PURPOSE in the script. -->", "",
           f"{len(tables)} tables, created by {len([f for f in d['flyway'] if f['version']])} versioned migrations. "
           "How the layer is organised, why, and the lifecycles are in [data-layer.md](data-layer.md); the picture is "
           "[diagrams/erd-backend.md](diagrams/erd-backend.md).", "",
           "## Migration history", "", "| # | Version | Script | Description | Applied |", "| --- | --- | --- | --- | --- |"]
    for f in d["flyway"]:
        out.append(f"| {f['installed_rank']} | {f['version'] or 'repeatable'} | `{f['script']}` | {f['description']} | {'yes' if f['success'] else 'FAILED'} |")
    out += ["", "## Tables", "", "| Table | Purpose |", "| --- | --- |"]
    for t in tables:
        out.append(f"| [{t}](#{t}) | {TABLE_PURPOSE.get(t, '')} |")
    out.append("")

    for t in tables:
        out += [f"## {t}", "", TABLE_PURPOSE.get(t, ""), "", "| Column | Type | Null | Default | Notes |", "| --- | --- | --- | --- | --- |"]
        for c in by_table[t]:
            notes = []
            if c["column_name"] in pk.get(t, []):
                notes.append("PK")
            if (t, c["column_name"]) in fk:
                notes.append("FK -> " + fk[(t, c["column_name"])])
            if (t, c["column_name"]) in uk:
                notes.append("unique")
            if c["is_generated"] == "ALWAYS":
                notes.append("generated: " + (c["generation_expression"] or ""))
            default = (c["column_default"] or "").replace("|", "\\|")
            out.append(f"| `{c['column_name']}` | {col_type(c)} | {'yes' if c['is_nullable'] == 'YES' else 'no'} | `{default}` | {'; '.join(notes)} |"
                       if default else
                       f"| `{c['column_name']}` | {col_type(c)} | {'yes' if c['is_nullable'] == 'YES' else 'no'} |  | {'; '.join(notes)} |")
        checks = [k for k in cons.get(t, []) if k["ty"] in ("c", "x") or (k["ty"] == "u" and k["n"].endswith("_key") is False)]
        if checks:
            out += ["", "Rules enforced by the database:", ""]
            for k in checks:
                kind = {"c": "check", "x": "exclusion", "u": "unique"}[k["ty"]]
                out.append(f"- `{k['n']}` ({kind}): `{k['def']}`")
        if idx.get(t):
            out += ["", "Indexes:", ""]
            for i in idx[t]:
                out.append(f"- `{i['indexname']}`: `{i['indexdef'].split(' USING ', 1)[-1]}`")
        out.append("")

    out += ["## Triggers", ""]
    for g in d["triggers"] or []:
        out.append(f"- `{g['trigger_name']}` on `{g['event_object_table']}`: {g['action_timing']} {g['event_manipulation']}")
    out += ["", "## Platform settings (reference data from the migrations)", "",
            "| Key | Value | Meaning |", "| --- | --- | --- |"]
    for s in d["settings"]:
        out.append(f"| `{s['setting_key']}` | `{s['setting_value']}` | {s['description'] or ''} |")
    out.append("")
    Path(doc_path).write_text("\n".join(out), encoding="utf-8", newline="\n")

    erd = ["# Backend ERD (PostgreSQL)", "",
           "<!-- Generated by docs/tools/schema_dictionary.py from the migrated database. Do not edit by hand. -->", "",
           "Database model of the PARI backend as created by the Flyway migrations. Columns, constraints, indexes and the meaning of "
           "each table are in [../data-dictionary.md](../data-dictionary.md); lifecycles and rules are in [../data-layer.md](../data-layer.md).", "",
           "```mermaid", "erDiagram"]
    seen = set()
    for parent, child, col in sorted(fk_edges):
        if (parent, child, col) not in seen:
            seen.add((parent, child, col))
            erd.append(f'    {parent.upper()} ||--o{{ {child.upper()} : "{col}"')
    for t in tables:
        erd.append(f"    {t.upper()} {{")
        for c in by_table[t]:
            tag = []
            if c["column_name"] in pk.get(t, []):
                tag.append("PK")
            if (t, c["column_name"]) in fk:
                tag.append("FK")
            if (t, c["column_name"]) in uk:
                tag.append("UK")
            ty = col_type(c).replace("(", "_").replace(")", "").replace(",", "_").replace(" ", "_")
            erd.append(f"        {ty} {c['column_name']}" + (f" {', '.join(tag)}" if tag else ""))
        erd.append("    }")
    erd += ["```", ""]
    Path(erd_path).write_text("\n".join(erd), encoding="utf-8", newline="\n")
    print(f"wrote {doc_path} and {erd_path}: {len(tables)} tables, {len(fk_edges)} foreign keys")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
