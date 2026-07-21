# Privacy model

Omni is capable of processing highly sensitive information because Android Accessibility can expose visible text, content descriptions, view identifiers, application names, and screenshots.

## Data sent off device

During an agent run, the Android app can send the configured backend:

- the user's spoken or typed goal;
- visible accessibility-node labels and geometry;
- the foreground app identifier;
- an annotated screenshot when the screen changes;
- recent model responses and action results;
- a user-confirmed memory of successful actions.

The backend can forward prompt content and screenshots to the selected LLM provider. Speech audio may be sent to the configured speech provider. Consult those providers' terms and retention controls before use.

## Local safeguards

- A sensitive-app guard suspends and disables Omni for known banking and financial apps.
- Screenshots are not captured while the guard is active.
- Memories are saved only after explicit confirmation.
- The app caps context size and does not intentionally request passwords or authentication secrets.

These controls are imperfect. Package lists and labels vary by region and can become outdated. Never rely on Omni around passwords, one-time codes, banking, payments, medical records, private messages, or other high-risk data.

## Self-hosting responsibilities

Operators control backend logs, database retention, model providers, backups, transport security, and account access. Use HTTPS outside localhost, minimize logs, restrict database access, configure provider retention where possible, and delete data no longer needed.

The public repository does not operate a service or make a universal privacy promise for third-party builds. An official hosted service must publish its own binding privacy policy and data-safety disclosures.
