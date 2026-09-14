# Requirements

The requirement documents are maintained in `docs/review-1/` so they sit next to the Review-1 rubric material:

| Document | Content |
|---|---|
| [01-problem-analysis.md](../review-1/01-problem-analysis.md) | Problems, root causes, stakeholders, solution, scope, assumptions, constraints |
| [02-functional-requirements.md](../review-1/02-functional-requirements.md) | FR-01 … FR-30 with acceptance criteria and implementing classes |
| [03-non-functional-requirements.md](../review-1/03-non-functional-requirements.md) | NFR-01 … NFR-15, verification, academic-vs-production limitations |

## Rubric traceability

| Review-1 rubric | Primary evidence |
|---|---|
| Problem analysis & requirement specification | 01, 02, 03; architecture diagrams |
| Microservice identification & service discovery | 04, 05; Eureka dashboard; `RestClientConfig`; load-balancing demo |
| JWT authentication | 06; `auth-service`; `SecurityConfig` in every service; security tests |
| API Gateway configuration | 07; `api-gateway`; `GatewaySecurityIntegrationTest` |
| Supporting | 08 database design, 09 booking workflow, 10 demonstration guide, `docs/api/api-reference.md` |
