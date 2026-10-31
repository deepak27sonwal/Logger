# Lead Logger

A native Android app for **company-owned devices** that captures call log
and SMS activity and syncs it, instantly, to a Supabase backend — so sales
follow-ups on customer calls/texts don't get missed. It is built for
**consent-based employee monitoring**, not covert tracking:

- First launch requires signing in with a company account, then scrolling
  through and explicitly **accepting a consent/disclosure screen** before
  anything is read from the device. Declining signs the employee out.
- Whenever monitoring is active, Android forces (and this app shows) a
  **permanent notification** — there is no way to run this hidden on modern
  Android, by design.
- The home screen has a one-tap **"Stop monitoring & sign out"** control
  that immediately halts all capture and uploads.
- The Supabase schema enforces **consent at the database level**: a row
  can't be inserted into `call_logs`/`sms_logs` for an employee until their
  `employees.consent_given_at` is set, regardless of what the app does.

## Important: this cannot be a normal Play Store app

Google Play's [permitted-use policy for Call Log/SMS permissions](https://support.google.com/googleplay/android-developer/answer/10208820)
restricts `READ_CALL_LOG`/`READ_SMS` to apps that are the user's default
SMS or Phone handler — a sales-lead-tracking app won't qualify. This app is
meant to be distributed privately: side-loaded APK, your own MDM (e.g.
Android Enterprise), or an internal app distribution tool — **not** the
public Play Store. Make sure employees are informed this is expected and
that the device is the employer's, per your jurisdiction's monitoring /
wiretap disclosure laws before rolling this out.

## Architecture

- **`ui/LoginActivity`** — email/password sign-in against Supabase Auth.
- **`ui/ConsentActivity`** — the disclosure screen; on accept, records
  consent server-side, requests the Android runtime permissions, and
  starts monitoring.
- **`service/MonitorService`** — foreground service (persistent
  notification, `dataSync` type). Registers `ContentObserver`s on the
  CallLog and SMS providers so new rows sync within moments of being
  written, plus a 60s fallback sweep.
- **`service/SmsReceivedReceiver`** — manifest-registered receiver for
  `SMS_RECEIVED`, nudges the service to sync immediately on incoming SMS.
- **`service/BootReceiver`** — resumes monitoring after a reboot, only if
  consent was already given and monitoring was already on.
- **`sync/SyncRepository`** — reads CallLog/SMS content providers since the
  last synced timestamp and upserts to Supabase (deduped via a unique
  constraint on `(employee_id, native_call_id)` / `(employee_id, native_sms_id)`).
- **`data/SupabaseClient`** — small OkHttp-based Supabase Auth + PostgREST
  client (password login, token refresh, upserts). No SDK version pinning
  guesswork — just the documented REST endpoints.
- **`ui/MainActivity`** — status screen: signed-in employee, monitoring
  state, last sync time, and the stop/start control.

## Backend (Supabase project "Callanalysis")

- URL: `https://nveqlyolozbaobvjmitd.supabase.co`
- Schema: [`supabase/migrations/20261031000000_lead_logger_schema.sql`](supabase/migrations/20261031000000_lead_logger_schema.sql)
  — already applied to the live project.
- Tables: `employees` (one row per signed-in employee, holds consent
  timestamp/version and `is_admin`), `call_logs`, `sms_logs`.
- **Row Level Security** on every table: an employee can only insert/read
  their own rows, and only after `consent_given_at` is set; a row with
  `employees.is_admin = true` can read everything (for your lead-follow-up
  dashboard). To make yourself an admin so you can query all data:
  ```sql
  update employees set is_admin = true where email = 'you@company.com';
  ```
- The anon/publishable key baked into `app/build.gradle.kts` is safe to
  ship — RLS is what actually protects the data, not key secrecy.

### Creating employee accounts

Employees sign in with Supabase Auth credentials. Create them via the
Supabase dashboard (Authentication → Users → Add user) or the `auth.users`
admin API; the app upserts the matching `employees` row on first login.

## Building

Open in Android Studio (or `./gradlew assembleDebug` once you've run
`gradle wrapper` locally — the wrapper jar isn't committed). Requires
JDK 17. `minSdk 26`, `targetSdk 34`.

## Not included yet

- An admin/lead dashboard to actually browse the synced data (today it's
  just rows in Supabase — query them directly, or ask for a dashboard next).
- Pairing flow beyond plain login (e.g. QR/install-code per device).
- Push-based (as opposed to poll + ContentObserver) delivery guarantees if
  the app is killed by aggressive OEM battery optimizers — consider
  exempting the app from battery optimization on managed devices.
