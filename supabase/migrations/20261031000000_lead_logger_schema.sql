-- Lead tracking: employee call-log & SMS capture
-- Every table is scoped to a signed-in employee identity (auth.users),
-- consent is recorded, and RLS restricts writes/reads to the employee's
-- own rows unless the caller is flagged as admin.

create table public.employees (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text not null,
  email text not null,
  employee_code text,
  is_admin boolean not null default false,
  consent_given_at timestamptz,
  consent_version text,
  device_model text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table public.employees is
  'One row per employee who has signed into the Lead Logger app. consent_given_at must be set before any call_logs/sms_logs rows can be inserted.';

create table public.call_logs (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id) on delete cascade,
  native_call_id text not null, -- Android CallLog._ID, for de-duplication
  phone_number text,
  call_type text not null check (call_type in ('incoming','outgoing','missed','rejected','blocked','voicemail','unknown')),
  call_time timestamptz not null,
  duration_seconds integer not null default 0,
  device_id text,
  synced_at timestamptz not null default now(),
  unique (employee_id, native_call_id)
);

create index call_logs_employee_time_idx on public.call_logs (employee_id, call_time desc);

create table public.sms_logs (
  id uuid primary key default gen_random_uuid(),
  employee_id uuid not null references public.employees(id) on delete cascade,
  native_sms_id text not null, -- Android SMS content provider _ID
  address text,
  sms_type text not null check (sms_type in ('inbox','sent','draft','outbox','failed','queued','unknown')),
  body text,
  sms_time timestamptz not null,
  device_id text,
  synced_at timestamptz not null default now(),
  unique (employee_id, native_sms_id)
);

create index sms_logs_employee_time_idx on public.sms_logs (employee_id, sms_time desc);

alter table public.employees enable row level security;
alter table public.call_logs enable row level security;
alter table public.sms_logs enable row level security;

-- employees: a signed-in user can see/update only their own row;
-- an admin (is_admin = true on their own row) can see every row.
create or replace function public.is_admin()
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select coalesce((select is_admin from public.employees where id = auth.uid()), false);
$$;

create policy "employees select own or admin"
  on public.employees for select
  using (id = auth.uid() or public.is_admin());

create policy "employees insert own"
  on public.employees for insert
  with check (id = auth.uid());

create policy "employees update own or admin"
  on public.employees for update
  using (id = auth.uid() or public.is_admin())
  with check (id = auth.uid() or public.is_admin());

-- call_logs / sms_logs: an employee may insert/select only rows tagged
-- with their own employee_id, and only after consent is recorded.
create policy "call_logs insert own after consent"
  on public.call_logs for insert
  with check (
    employee_id = auth.uid()
    and exists (
      select 1 from public.employees e
      where e.id = auth.uid() and e.consent_given_at is not null
    )
  );

create policy "call_logs select own or admin"
  on public.call_logs for select
  using (employee_id = auth.uid() or public.is_admin());

create policy "sms_logs insert own after consent"
  on public.sms_logs for insert
  with check (
    employee_id = auth.uid()
    and exists (
      select 1 from public.employees e
      where e.id = auth.uid() and e.consent_given_at is not null
    )
  );

create policy "sms_logs select own or admin"
  on public.sms_logs for select
  using (employee_id = auth.uid() or public.is_admin());

-- keep updated_at current on employees
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger employees_set_updated_at
  before update on public.employees
  for each row
  execute function public.set_updated_at();
