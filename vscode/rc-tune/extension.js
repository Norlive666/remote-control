"use strict";

const { spawn } = require("node:child_process");
const { networkInterfaces } = require("node:os");
const path = require("node:path");
const { createInterface } = require("node:readline");

const HTTP_PORT = 8765;
const START_TIMEOUT = 10000;
const STOP_TIMEOUT = 10000;
let stopExtension;

async function debugTarget(session) {
    if (session?.type !== "mcu-debug") {
        throw new Error("请先按 F5 启动 MCU AI Debug，并选中要连接的调试会话。");
    }
    const args = await session.customRequest("get-arguments");
    if (args?.servertype !== "openocd") {
        throw new Error("请选择由 MCU AI Debug 启动的 OpenOCD 会话。");
    }
    if (args.numberOfProcessors > 1 || args.targetProcessor > 0) {
        throw new Error("当前版本支持单核 OpenOCD 调试会话。");
    }
    const tclPort = args.pvtPorts?.tclPort?.localPort;
    if (!Number.isInteger(tclPort) || tclPort < 1 || tclPort > 65535) {
        throw new Error("当前调试会话没有可用的 OpenOCD Tcl 端口。");
    }
    if (typeof args.executable !== "string" || !args.executable) {
        throw new Error("调试会话没有提供 ELF。请检查 launch.json 的 executable。");
    }
    return { elf: args.executable, tclPort };
}

function launchProcess(command, args, onLine) {
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
    let markReady;
    let failReady;
    let finishExit;
    const ready = new Promise((resolve, reject) => { markReady = resolve; failReady = reject; });
    const exited = new Promise(resolve => { finishExit = resolve; });
    for (const input of [child.stdout, child.stderr]) {
        createInterface({ input, crlfDelay: Infinity }).on("line", line => {
            onLine(line);
            const match = /^RC_TUNE_READY (\d+)$/.exec(line);
            if (match) markReady(Number(match[1]));
        });
    }
    child.stdin.on("error", () => {}); // The process may exit just before stdin is closed.
    child.on("error", error => {
        failReady(error);
        finishExit({ error });
    });
    child.on("close", (code, signal) => {
        failReady(new Error(`rc-tune 已退出（${code ?? signal}）。`));
        finishExit({ code, signal });
    });

    const startup = setTimeout(() => {
        failReady(new Error("等待 rc-tune 启动超时。"));
        child.stdin.end();
    }, START_TIMEOUT);
    ready.then(() => clearTimeout(startup), () => clearTimeout(startup));

    let stopping;
    return {
        ready,
        exited,
        stop() {
            if (!stopping) {
                child.stdin.end();
                const timer = setTimeout(() => child.kill(), STOP_TIMEOUT);
                stopping = exited.finally(() => clearTimeout(timer));
            }
            return stopping;
        },
    };
}

function activate(context) {
    const vscode = require("vscode");
    const output = vscode.window.createOutputChannel("rc-tune");
    const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left);
    let active;

    function refresh() {
        status.text = active?.port ? "$(debug-stop) rc-tune 已启动" :
            active ? "$(sync~spin) rc-tune 启动中" : "$(plug) rc-tune";
        status.tooltip = active ? "停止手机调试链路" : "启动手机调试链路";
        status.command = active ? "rcTune.stop" : "rcTune.start";
        if (active || vscode.debug.activeDebugSession?.type === "mcu-debug") status.show();
        else status.hide();
    }

    async function connectionInfo() {
        const entry = active;
        if (!entry?.port || entry.cancelled) {
            vscode.window.showInformationMessage("请先启动 rc-tune。");
            return;
        }
        const addresses = [...new Set(Object.values(networkInterfaces()).flat()
            .filter(info => info && !info.internal && info.family === "IPv4")
            .map(info => info.address))];
        const host = addresses.length > 1
            ? await vscode.window.showQuickPick(addresses, { placeHolder: "选择与手机同一局域网的电脑 IP" })
            : addresses[0];
        if (!host || active !== entry || entry.cancelled) {
            if (!addresses.length) vscode.window.showInformationMessage("暂无可用局域网 IPv4 地址。");
            return;
        }
        const info = `IP ${host} · 端口 ${entry.port}`;
        const selected = await vscode.window.showInformationMessage(info, "复制");
        if (selected === "复制") await vscode.env.clipboard.writeText(info);
    }

    async function stop() {
        const entry = active;
        if (!entry || entry.stopping) return entry?.stopping;
        entry.cancelled = true;
        entry.stopping = (async () => {
            const result = await entry.bridge?.stop();
            if (active === entry) active = undefined;
            refresh();
            if (result && result.code !== 0) {
                output.show(true);
                vscode.window.showWarningMessage("rc-tune 已停止，未能确认写入停止状态。请查看输出。");
            }
        })();
        return entry.stopping;
    }

    async function start() {
        if (active) {
            if (active.port && !active.cancelled) await connectionInfo();
            return;
        }
        const session = vscode.debug.activeDebugSession;
        const entry = { sessionId: session?.id };
        active = entry;
        refresh();
        try {
            const target = await debugTarget(session);
            if (active !== entry || entry.cancelled || vscode.debug.activeDebugSession?.id !== entry.sessionId) {
                if (active === entry && !entry.cancelled) {
                    active = undefined;
                    refresh();
                }
                return;
            }
            const folder = context.asAbsolutePath("bridge");
            const runtime = path.join(folder, "python", process.platform === "win32" ? "python.exe" : "bin/python3.12");
            entry.bridge = launchProcess(runtime, [
                "-I", "-B", "-u", path.join(folder, "run.py"), "serve", "--backend", "openocd-tcl",
                "--elf", target.elf, "--ocd-port", String(target.tclPort),
                "--port", String(HTTP_PORT), "--allow-write", "--stdio-lifecycle",
            ], line => output.appendLine(line));
            entry.port = await entry.bridge.ready;
            entry.bridge.exited.then(result => {
                if (active !== entry || entry.cancelled) return;
                active = undefined;
                refresh();
                if (result.code !== 0) {
                    output.show(true);
                    vscode.window.showWarningMessage("rc-tune 意外退出。请查看输出。");
                }
            });
            if (active === entry && !entry.cancelled) {
                refresh();
                await connectionInfo();
            }
        } catch (error) {
            if (active !== entry || entry.cancelled) return;
            entry.cancelled = true;
            await entry.bridge?.stop();
            if (active === entry) active = undefined;
            refresh();
            output.appendLine(String(error));
            output.show(true);
            vscode.window.showErrorMessage(`rc-tune 启动失败：${error.message}`);
        }
    }

    stopExtension = stop;
    context.subscriptions.push(
        output, status,
        vscode.commands.registerCommand("rcTune.start", start),
        vscode.commands.registerCommand("rcTune.stop", stop),
        vscode.commands.registerCommand("rcTune.connectionInfo", connectionInfo),
        vscode.debug.onDidChangeActiveDebugSession(refresh),
        vscode.debug.onDidTerminateDebugSession(session => {
            if (session.id === active?.sessionId) void stop();
        }),
    );
    refresh();
}

function deactivate() {
    return stopExtension?.();
}

module.exports = { activate, deactivate, debugTarget, launchProcess };
