import { renameSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

const MARKER = "--tuilaunch-";
const STATE_FILE = "tuilaunch-active.json";
const EVENTS = ["session_start", "session_switch", "session_branch", "session_tree", "agent_end"];

function writeActiveSession(ctx) {
  try {
    const sessionDir = ctx.sessionManager.getSessionDir();
    if (!sessionDir || !basename(sessionDir).includes(MARKER)) return;
    const sessionFile = ctx.sessionManager.getSessionFile();
    if (!sessionFile) return;
    const target = join(sessionDir, STATE_FILE);
    const staging = `${target}.tmp`;
    const payload = JSON.stringify({
      sessionFile,
      sessionId: ctx.sessionManager.getSessionId(),
      updatedAt: new Date().toISOString(),
    });
    writeFileSync(staging, `${payload}\n`, "utf8");
    renameSync(staging, target);
  } catch {}
}

export default function tuilaunchFollowSession(pi) {
  for (const event of EVENTS) {
    pi.on(event, (_event, ctx) => writeActiveSession(ctx));
  }
}
