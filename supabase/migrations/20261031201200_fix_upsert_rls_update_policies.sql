-- The app syncs with an upsert (Prefer: resolution=merge-duplicates), which
-- Postgres implements as INSERT ... ON CONFLICT DO UPDATE. The original
-- migration only granted INSERT + SELECT on call_logs/sms_logs, so every
-- re-send of an already-synced row (the ON CONFLICT -> UPDATE path) was
-- rejected by RLS with 42501 (insufficient_privilege). Add matching UPDATE
-- policies, identical in spirit to the INSERT ones: an employee may only
-- touch their own rows, and only once consent is recorded.

create policy "call_logs update own after consent"
  on public.call_logs for update
  using (employee_id = auth.uid())
  with check (
    employee_id = auth.uid()
    and exists (
      select 1 from public.employees e
      where e.id = auth.uid() and e.consent_given_at is not null
    )
  );

create policy "sms_logs update own after consent"
  on public.sms_logs for update
  using (employee_id = auth.uid())
  with check (
    employee_id = auth.uid()
    and exists (
      select 1 from public.employees e
      where e.id = auth.uid() and e.consent_given_at is not null
    )
  );
