# Operations source reference

Use this index to locate checked-in database scripts, shell tools, and Python
seed or smoke-test entry points. Links identify source files; their presence
does not establish that they have run successfully in any environment.

Before running a tool, read its implementation and the README in its directory
(where available), and verify its target environment. Example JSON files are
configuration templates rather than live credentials or deployment settings.
SQL execution order and prerequisites must be checked in the relevant service
documentation. This index is a navigation aid, not an execution runbook.

| Area | Reference | Use |
| --- | --- | --- |
| auth-service | [V1__init_schema.sql](../auth-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| auth-service | [V2__seed_sample_data.sql](../auth-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| booking-service | [V1__init_schema.sql](../booking-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| booking-service | [V2__seed_sample_data.sql](../booking-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| facility-service | [V1__init_schema.sql](../facility-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| facility-service | [V2__seed_sample_data.sql](../facility-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| payment-service | [V1__init_schema.sql](../payment-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| payment-service | [V2__seed_sample_data.sql](../payment-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| pos-retail-service | [V1__init_schema.sql](../pos-retail-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| pos-retail-service | [V2__seed_sample_data.sql](../pos-retail-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| slot-hold-engine | [V1__init_schema.sql](../slot-hold-engine/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| staff-operations-service | [V1__init_schema.sql](../staff-operations-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| staff-operations-service | [V2__seed_sample_data.sql](../staff-operations-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| subscription-service | [V1__init_schema.sql](../subscription-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| subscription-service | [V2__seed_sample_data.sql](../subscription-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| voucher-service | [V1__init_schema.sql](../voucher-service/database/V1__init_schema.sql) | Inspect the checked-in SQL schema script for this service. |
| voucher-service | [V2__seed_sample_data.sql](../voucher-service/database/V2__seed_sample_data.sql) | Inspect the checked-in SQL seed script for this service. |
| tools | [compose-seed.sh](../tools/compose-seed.sh) | Inspect this shell script and its prerequisites before running it. |
