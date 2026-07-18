# Paper server performance troubleshooting

Use this checklist when a Paper-based Minecraft server develops low TPS, long ticks, or unexplained lag.

## Capture useful evidence first

1. Record a Spark profile during the slowdown, not during an idle period.
2. Include the Paper build, Java version, player count, and the approximate time the problem began.
3. Paste the relevant log excerpt through [mclo.gs](https://mclo.gs/) after checking that secrets, IPs, and player-identifying information are removed.
4. Note recent plugin, datapack, configuration, or world changes.
5. Do not share server-panel credentials, SSH access, private keys, or full unredacted logs.

A practical starting point is Paper's Spark profiler command:

```text
/spark profiler start --timeout 120
```

Reproduce the lag during the capture, then use the result URL to identify expensive plugins, entities, chunks, scheduled tasks, or synchronous I/O. Treat profiler findings as evidence: change one thing at a time, measure again, and keep a rollback path.

## Optional fixed-scope review

[TPS Rescue](https://tps-rescue-hazel.airheadhazelnut.chatgpt.site/) offers a free sample audit and a paid, evidence-backed review for operators who want a second set of eyes. The paid scope is a flat **$39**, uses only redacted Spark and mclo.gs links, requires no credentials, and does not promise a particular TPS result.

[Open a private request](https://tps-rescue-hazel.airheadhazelnut.chatgpt.site/request) to receive a private ticket URL, approve the exact scope, and pay only after accepting it. Payment is verified on-chain through native USDC on Base mainnet.
