const assert = require("node:assert/strict");
const { test } = require("node:test");
const { debugTarget, launchProcess } = require("./extension");

test("uses only the resolved ELF and local Tcl port from the selected F5 session", async () => {
    const executable = "C:/Program Files/固件/build/Debug/Gimbal.elf";
    const session = {
        type: "mcu-debug",
        async customRequest(command) {
            assert.equal(command, "get-arguments");
            return {
                servertype: "openocd", executable,
                pvtPorts: { tclPort: { localPort: 50123, remotePort: 40123 }, gdbPort: { localPort: 50000 } },
            };
        },
    };
    assert.deepEqual(await debugTarget(session), { elf: executable, tclPort: 50123 });
});

test("rejects other debug adapters and sessions without a valid local Tcl endpoint", async () => {
    await assert.rejects(debugTarget({type: "cppdbg"}), /MCU AI Debug/);
    for (const port of [undefined, 0, -1, 65536]) {
        const session = {
            type: "mcu-debug",
            async customRequest() {
                return {servertype: "openocd", executable: "/tmp/app.elf", pvtPorts: {tclPort: {localPort: port}}};
            },
        };
        await assert.rejects(debugTarget(session), /Tcl/);
    }
});

test("waits for bridge readiness and closes the child through stdin", async () => {
    const script = 'process.stdin.resume(); console.log("RC_TUNE_READY 8765"); process.stdin.on("end", () => console.log("safe stop"));';
    const lines = [];
    const bridge = launchProcess(process.execPath, ["-e", script], line => lines.push(line));
    try {
        assert.equal(await bridge.ready, 8765);
        assert.equal((await bridge.stop()).code, 0);
        assert.ok(lines.includes("safe stop"));
    } finally {
        await bridge.stop();
    }
});

test("a bridge that dies during startup reports a startup error", async () => {
    const bridge = launchProcess(process.execPath, ["-e", "process.exit(3)"], () => {});
    await assert.rejects(bridge.ready, /3/);
    assert.equal((await bridge.exited).code, 3);
});
