# Evaluation: iQOO Recall

**Status (2026-09-26):** No device results yet. The phone hasn't been connected, so every on-device number below is still pending. What has been measured so far comes from the build machine and is labelled as such. The queries and runner are ready for the phone.

## 1. Retrieval: 20 queries, hit@3

- **Corpus:** the synthetic seed set (`tools/make_seed_docs.py`, listed in `tools/out/seed/seed_manifest.json`), indexed on the phone.
- **Hit rule:** a find or question query hits when a document of an expected type appears in the top 3 results. The pack query hits when it opens the expected checklist.
- **Where the queries live:** `app/src/main/assets/eval_queries.json`. Run them on the phone from Benchmark → "Run EVAL queries". This uses Qwen when it is loaded, and the rules parser otherwise.
- **Gate (brief Phase 3):** hit@3 ≥ 80% overall and ≥ 70% in each language.

| # | Lang | Query | Expected |
|---|---|---|---|
| 1 | en | show my salary slips | SALARY_SLIP |
| 2 | en | when does my health insurance expire | HEALTH_INSURANCE |
| 3 | en | car insurance policy | VEHICLE_INSURANCE |
| 4 | en | my PAN card | PAN |
| 5 | en | latest blood test report | MEDICAL_REPORT |
| 6 | en | hospital bill from my last admission | HOSPITAL_BILL |
| 7 | en | bank statement for last month | BANK_STATEMENT |
| 8 | te | నా ఆధార్ కార్డ్ చూపించు | AADHAAR |
| 9 | te | జీతం స్లిప్పులు చూపించు | SALARY_SLIP |
| 10 | te | కరెంట్ బిల్లు ఎంత | UTILITY_BILL |
| 11 | te (romanized) | naa health insurance ekkada undi | HEALTH_INSURANCE |
| 12 | te (romanized) | bank statement chupinchu | BANK_STATEMENT |
| 13 | te | ఆసుపత్రి బిల్లు | HOSPITAL_BILL |
| 14 | te (romanized) | Home loan ki documents ready cheyyi | home_loan checklist |
| 15 | hi | मेरा आधार कार्ड दिखाओ | AADHAAR |
| 16 | hi | पिछले महीने की सैलरी स्लिप | SALARY_SLIP |
| 17 | hi | मेरी मेडिकल रिपोर्ट | MEDICAL_REPORT |
| 18 | hi | गाड़ी का बीमा | VEHICLE_INSURANCE |
| 19 | hi | आभा कार्ड दिखाओ | HEALTH_ID_ABHA |
| 20 | hi | ऑफर लेटर या नौकरी का पत्र | EMPLOYMENT_LETTER |

### Results

| Run | Parser | Overall | en | te | hi |
|---|---|---|---|---|---|
| Offline proxy (JVM unit test `EvalRulesTest`): does the query map to the expected type or template? | rules | 20/20 | 7/7 | 7/7 | 6/6 |
| On the phone, hit@3 over indexed seed documents | rules | _pending_ | | | |
| On the phone, hit@3 over indexed seed documents | Qwen | _pending_ | | | |

The offline proxy checks only intent mapping. It is not a retrieval result.

## 2. Gatekeeper precision and recall (seed set)

| Run | Documents correctly kept | Non-documents correctly skipped | Precision | Recall |
|---|---|---|---|---|
| Build machine, Hugging Face reference embeddings, threshold 0.0 | 13/13 | 3/3 | 1.00 | 1.00 |
| On the phone, SigLIP2 TFLite on the NPU | _pending_ | _pending_ | | |

Margins on the build machine: documents fell between +0.031 and +0.108, and non-documents between −0.068 and −0.028.

## 3. Document typing (Phase 2 gate: ≥ 85% of seed documents correctly typed)

_Pending on the phone._ The classifier's unit tests pass on synthetic texts. They cover Aadhaar, PAN, Form 16 vs PAN, salary slips, bank statements, health insurance, and a SigLIP2 hint breaking a tie (`DocClassifierTest`).
