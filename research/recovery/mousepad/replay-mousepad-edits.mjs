import { readFileSync } from "fs";

const LF = String.fromCharCode(10);
const session = process.env.USERPROFILE + "/.codex/sessions/2026/07/08/rollout-2026-07-08T18-43-27-019f43e6-7edc-7eb2-87db-c6f36b37a76d.jsonl";
const target = "prototypes/mousepad-android-app/src/org/archphene/linux/kcalc/MainActivity.java";
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
let replayed = 0;
let failed = 0;

for (const line of readFileSync(session, "utf8").split(LF)) {
  if (!line) continue;
  let row;
  try { row = JSON.parse(line); } catch { continue; }
  const p = row.payload;
  if (row.type !== "response_item" || p?.type !== "custom_tool_call" || p.name !== "exec") continue;
  if (row.timestamp < "2026-07-11T11:26:00" || row.timestamp >= "2026-07-11T23:50:00") continue;
  const source = p.input || "";
  if (!source.includes(target) || !/(WriteAllText|Set-Content|Copy-Item|Move-Item)/.test(source)) continue;

  const commands = [];
  const tools = {
    exec_command: async arg => {
      if (arg?.cmd) commands.push(arg.cmd);
      return { output: "", exit_code: 0, wall_time_seconds: 0 };
    }
  };
  try {
    await new AsyncFunction("tools", "text", "image", "ALL_TOOLS", source)(
      tools, () => {}, () => {}, []
    );
  } catch (error) {
    failed++;
    process.stdout.write("capture-failed " + row.timestamp + " " + error + LF);
    continue;
  }

  for (let command of commands) {
    for (const stopWord of ["build-install-mousepad-app.ps1", "adb shell", "adb logcat"]) {
      const at = command.indexOf(stopWord);
      if (at >= 0) {
        const semi = Math.max(command.lastIndexOf(";", at), command.lastIndexOf(LF, at));
        command = command.slice(0, semi >= 0 ? semi : at);
      }
    }
    if (!command.trim()) continue;
    const result = Bun.spawnSync({
      cmd: [process.env.ProgramFiles + "/PowerShell/7/pwsh.exe", "-NoProfile", "-Command", command],
      cwd: process.cwd(),
      stdout: "pipe",
      stderr: "pipe",
    });
    replayed++;
    if (result.exitCode !== 0) {
      failed++;
      const err = result.stderr.toString().trim().split(LF).slice(-2).join(" ");
      process.stdout.write("replay-failed " + row.timestamp + " " + err + LF);
    }
  }
}
process.stdout.write(JSON.stringify({ replayed, failed }) + LF);