# Diagrams

Architecture diagrams of the PARI backend, written in [Mermaid](https://mermaid.js.org/). GitHub renders them directly in the file view, so open a file below to see the picture.

| Diagram | File | Shows |
| --- | --- | --- |
| Backend ERD | [erd-backend.md](erd-backend.md) | PostgreSQL tables, columns and relations |
| Frontend ERD | [erd-frontend.md](erd-frontend.md) | Client-side view models used by the React screens |
| Class diagram: domain | [class-domain.md](class-domain.md) | Entities, value objects and their relations |
| Class diagram: application | [class-application.md](class-application.md) | Controllers, services, ports and adapters, jobs, domain events |

The text explanation of each diagram (entity dictionary, field mapping, status lifecycle, responsibilities) is in the Confluence space *My Software Team*, under *PARI project documentation*:

- PARI data model (ERD): backend and frontend
- PARI backend class diagram
- PARI backend technology stack (Java)
- PARI backend REST API: endpoint list

## Working with the diagrams

- **Edit:** change the Mermaid code inside the `mermaid` fence and open a pull request, like any other change. Keep Confluence and these files in sync: when a diagram changes, update both.
- **Preview while editing:** GitHub preview, the "Mermaid" plugin in IntelliJ IDEA, or [mermaid.live](https://mermaid.live).
- **Export to PNG or SVG** (for the thesis document or slides): copy the code block into mermaid.live and use Actions, or from the command line:

  ```bash
  npm install -g @mermaid-js/mermaid-cli
  # extract the code block into a .mmd file first, then:
  mmdc -i erd-backend.mmd -o erd-backend.svg
  ```

- **Source of truth:** the Polish design document (figures 11.1, 11.2, 13.1, 13.2) is authoritative. When these diagrams and the document differ, fix the side that is wrong.
