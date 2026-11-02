# Documentation

Where documents live and how to keep them correct.

## Where things are

| What | Where | Notes |
| --- | --- | --- |
| Project design document (requirements F01-F25, NF01-NF15, diagrams, UI) | Polish `.docx`/`.pdf` in the shared Google Drive folder *Eng Project PARI* | **Authoritative.** When another document disagrees, this one wins. |
| Team documents: project plan (English), technology stack, data model, class diagram, API endpoint list | Confluence space *My Software Team*, under *PARI project documentation* | Working copies for daily use. |
| System documentation (what exists today) | [backend-concepts.md](backend-concepts.md), [data-layer.md](data-layer.md), [frontend-backend-interaction.md](frontend-backend-interaction.md) | English, describes the code on `main`; planned parts are marked *planned*. |
| Generated reference | [data-dictionary.md](data-dictionary.md) (tables, columns, constraints), [api-reference.md](api-reference.md) (every endpoint and schema) | Produced from the running application by the scripts in [tools/](tools); regenerate, do not edit by hand. |
| Diagrams as code | [diagrams/](diagrams) (Mermaid, rendered by GitHub) | Same diagrams as in Confluence; `erd-backend.md` is generated. |
| Architecture decision records | [adr/](adr) | Short, one decision per file. |
| How to build, run and test | [README.md](../README.md) | |
| Workflow and rules | [CONTRIBUTING.md](../CONTRIBUTING.md), [AGENTS.md](../AGENTS.md) | |
| Tasks and backlog | Jira board, project `PARI` | |

## Regenerating the generated documents

Run the backend against a migrated database (`SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`, or the clean variant in `docker compose`), then:

```bash
curl -s http://localhost:8080/v3/api-docs -o openapi.json
python docs/tools/openapi_to_markdown.py openapi.json docs/api-reference.md       # appends docs/api-reference-notes.md

psql -U pari -d pari -At -f docs/tools/schema.sql > schema.json                     # for example via docker exec -i <container> psql ...
python docs/tools/schema_dictionary.py schema.json docs/data-dictionary.md docs/diagrams/erd-backend.md
```

Both scripts use only the Python standard library. `api-reference-notes.md` (roles, errors, conventions) and the table descriptions inside `schema_dictionary.py` are the hand-written parts: update them when behaviour changes. The `README.md` rule applies: a PR that changes an endpoint or a migration regenerates the matching file.

## Rules

1. **One source of truth per fact.** Do not copy long passages between documents; link instead. The two intentional copies are the Mermaid diagrams (repo and Confluence); change both together.
2. **Update docs in the same PR as the change.** A PR that changes the schema, an entity, an endpoint or a business rule updates the matching diagram, table or page, or says in the description why not.
3. **Say what is not built yet.** Mark planned parts as *planned*. Never document behaviour that does not exist.
4. **Use requirement ids.** Refer to F/NF ids so work can be traced back to the plan.
5. **Decisions get an ADR** when they are hard to reverse or affect several modules (framework, database, payment provider, security model, naming of the API). Copy [adr/0000-template.md](adr/0000-template.md), number it, keep it to one page. Do not edit an accepted ADR; add a new one that supersedes it.
6. **New Confluence documents** are child pages of *PARI project documentation*, with a row added to its *Documents* table.
7. **Language:** English for everything in this repository and in the team Confluence pages; Polish only for the university design document.
8. **Diagrams:** Mermaid in `docs/diagrams/*.md`, one diagram per file, validated by opening the file on GitHub or in [mermaid.live](https://mermaid.live) before merging.
9. **Placeholders:** values the team has not decided (commission rate, penalty rates, limits) are marked `PLACEHOLDER` where they appear and listed in the PR that introduces them.

## Checklist for a documentation PR

- The text is accurate for the code on the branch.
- Links work; file and class names exist.
- Planned vs implemented is clear.
- Diagrams render on GitHub.
- Confluence is updated if the repo document is a copy.
