-- Let either participant remove only inactive request history.
drop policy if exists "Participants can delete inactive sharing requests" on public.sharing_requests;
create policy "Participants can delete inactive sharing requests"
on public.sharing_requests
for delete
to authenticated
using (
  (select auth.uid()) in (sender_id, receiver_id)
  and status in ('pending', 'rejected', 'cancelled')
);
