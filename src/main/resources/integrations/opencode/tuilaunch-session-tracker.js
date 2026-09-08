import { renameSync, writeFileSync } from "node:fs";

const POLL_MS = 500;

export default {
  id: "tuilaunch-session-tracker",
  async tui(api) {
    const target = process.env.TUILAUNCH_OPENCODE_STATE;
    if (!target) return;

    let last = "";

    const record = () => {
      const staging = `${target}.${process.pid}.tmp`;
      try {
        const route = api.route.current;
        if (!route || route.name !== "session") return;
        const sessionId = route.params && route.params.sessionID;
        if (typeof sessionId !== "string" || !sessionId.startsWith("ses_") || sessionId === last) return;
        if (api.state.ready && api.state.session.get(sessionId)?.parentID) return;
        writeFileSync(staging, `${JSON.stringify({ sessionId, updatedAt: Date.now() })}\n`, "utf8");
        renameSync(staging, target);
        last = sessionId;
      } catch {
        last = "";
      }
    };

    const timer = setInterval(record, POLL_MS);
    if (timer.unref) timer.unref();
    api.lifecycle.onDispose(() => clearInterval(timer));
    record();
  },
};
