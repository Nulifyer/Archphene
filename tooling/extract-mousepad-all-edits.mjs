import { readFileSync, writeFileSync } from "fs";

const LF = String.fromCharCode(10);
const session = process.env.USERPROFILE + "/.codex/sessions/2026/07/08/rollout-2026-07-08T18-43-27-019f43e6-7edc-7eb2-87db-c6f36b37a76d.jsonl";
const target = "prototypes/mousepad-android-app/src/org/archphene/linux/kcalc/MainActivity.java";
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const replay = [];
let captureFailed = 0;

for (const line of readFileSync(session, "utf8").split(LF)) {
  if (!line) continue;
  let row;
  try { row = JSON.parse(line); } catch { continue; }
  const p = row.payload;
  if (row.type !== "response_item" || p?.type !== "custom_tool_call" || p.name !== "exec") continue;
  if (row.timestamp < "2026-07-11T07:26:00" || row.timestamp >= "2026-07-11T23:50:00") continue;
  const source = p.input || "";
  if (!source.includes(target)) continue;
  if (!/(WriteAllText|Set-Content|Copy-Item|Move-Item|apply_patch)/.test(source)) continue;

  const captured = [];
  const tools = {
    exec_command: async arg => {
      if (arg?.cmd && /(WriteAllText|Set-Content|Copy-Item|Move-Item)/.test(source)) {
        captured.push({ kind: "shell", value: arg.cmd });
      }
      return { output: "", exit_code: 0, wall_time_seconds: 0 };
    },
    apply_patch: async patch => {
      if (typeof patch === "string" && patch.includes(target)) {
        captured.push({ kind: "patch", value: patch });
      }
      return "";
    }
  };
  try {
    await new AsyncFunction("tools", "text", "image", "ALL_TOOLS", source)(
      tools, () => {}, () => {}, []
    );
  } catch (error) {
    captureFailed++;
    continue;
  }

  for (const item of captured) {
    if (item.kind === "shell") {
      let command = item.value;
      for (const stopWord of ["build-install-mousepad-app.ps1", "adb shell", "adb logcat"]) {
        const at = command.indexOf(stopWord);
        if (at >= 0) {
          const semi = Math.max(command.lastIndexOf(";", at), command.lastIndexOf(LF, at));
          command = command.slice(0, semi >= 0 ? semi : at);
        }
      }
      if (command.trim()) replay.push({ timestamp: row.timestamp, kind: item.kind, value: command });
    } else {
      replay.push({ timestamp: row.timestamp, kind: item.kind, value: item.value });
    }
  }
}
writeFileSync("tooling/replay-mousepad-all.json", JSON.stringify({ captureFailed, replay }, null, 2));
const patchCount = replay.filter(x => x.kind === "patch").length;
process.stdout.write(JSON.stringify({ entries: replay.length, patches: patchCount, captureFailed }) + LF);