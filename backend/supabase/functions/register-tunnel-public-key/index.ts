import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply(405, { error: "POST required" });

  const authHeader = req.headers.get("Authorization") ?? "";
  const jwt = authHeader.replace(/^Bearer\s+/i, "");
  if (!jwt) return reply(401, { error: "Authentication required" });

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anon || !serviceKey) return reply(500, { error: "Server configuration incomplete" });

  const userClient = createClient(url, anon, {
    global: { headers: { Authorization: `Bearer ${jwt}` } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: authData, error: authError } = await userClient.auth.getUser(jwt);
  if (authError || !authData.user) return reply(401, { error: "Invalid session" });

  let payload: { request_id?: string; public_key?: string };
  try { payload = await req.json(); } catch { return reply(400, { error: "Invalid JSON" }); }

  const requestId = payload.request_id?.trim() ?? "";
  const publicKey = payload.public_key?.trim() ?? "";
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
    return reply(400, { error: "Invalid request_id" });
  }
  // WireGuard public keys are 32 bytes encoded as canonical base64.
  if (!/^[A-Za-z0-9+/]{42}[AQgw]=$/.test(publicKey)) {
    return reply(400, { error: "Invalid WireGuard public key" });
  }

  const { data: share, error: shareError } = await userClient
    .from("sharing_requests")
    .select("id,sender_id,receiver_id,quota_bytes,status")
    .eq("id", requestId)
    .maybeSingle();

  if (shareError || !share) return reply(404, { error: "Sharing request not found" });
  if (share.status !== "approved") return reply(409, { error: "Request must be approved before tunnel setup" });

  const isSender = authData.user.id === share.sender_id;
  const isReceiver = authData.user.id === share.receiver_id;
  if (!isSender && !isReceiver) return reply(403, { error: "Not a participant in this request" });

  const { data: existing, error: existingError } = await admin
    .from("tunnel_sessions")
    .select("id,status,sender_public_key,receiver_public_key")
    .eq("sharing_request_id", requestId)
    .maybeSingle();
  if (existingError) return reply(500, { error: "Could not read tunnel session" });
  if (existing?.status === "revoked" || existing?.status === "expired" || existing?.status === "failed") {
    return reply(409, { error: "This tunnel session is closed; create a new sharing request" });
  }

  const column = isSender ? "sender_public_key" : "receiver_public_key";
  const patch: Record<string, unknown> = { [column]: publicKey, updated_at: new Date().toISOString() };

  let result;
  if (existing) {
    const { data, error } = await admin.from("tunnel_sessions")
      .update(patch).eq("id", existing.id)
      .select("id,status,sender_public_key,receiver_public_key,quota_bytes")
      .single();
    result = { data, error };
  } else {
    const { data, error } = await admin.from("tunnel_sessions")
      .insert({
        sharing_request_id: share.id,
        sender_id: share.sender_id,
        receiver_id: share.receiver_id,
        quota_bytes: share.quota_bytes,
        ...patch,
      })
      .select("id,status,sender_public_key,receiver_public_key,quota_bytes")
      .single();
    result = { data, error };
  }
  if (result.error) return reply(500, { error: "Could not register public key" });

  return reply(200, {
    session_id: result.data.id,
    status: result.data.status,
    sender_key_registered: Boolean(result.data.sender_public_key),
    receiver_key_registered: Boolean(result.data.receiver_public_key),
    quota_bytes: result.data.quota_bytes,
    note: "Public key registered only. No tunnel endpoint is configured and no traffic is forwarded yet.",
  });
});
