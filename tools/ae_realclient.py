#!/usr/bin/env python3
"""SentinelAE against a real cheat client (localhost only): Minecraft with Meteor Client in a virtual display, its
own ESP, StorageESP and Tracers modules switched on. Behind walls they must have nothing to draw; through an open
window they must draw. Also checks that the rewritten chunks load in the genuine client without errors.
Reuses the client bench of SentinelAC (tools/realclient_probe.py).

  <venv>/bin/python tools/ae_realclient.py --bench ../SentinelAC/code/tools --paper paper.jar --java java \
      --jar SentinelAE.jar --portablemc <venv>/bin/portablemc --mc client/mc --client-java java21 \
      --target-bot tools/client/viewer.cjs --mojang-cache dir --work dir --accept-eula
"""
import argparse, os, shutil, subprocess, sys, time
from pathlib import Path


def main():
    p = argparse.ArgumentParser()
    for k in ('--bench', '--paper', '--jar', '--portablemc', '--mc', '--work', '--target-bot'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--client-java', required=True)
    p.add_argument('--client', default='fabric:1.21.11')
    p.add_argument('--client-heap', default='900m')
    p.add_argument('--plugin', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--bot-version', default='1.21.11')
    p.add_argument('--cheat', choices=['meteor', 'liquidbounce'], default='meteor')
    p.add_argument('--paper-world', type=Path, help='a paper-world.yml to start the world with (e.g. anti-xray on)')
    p.add_argument('--paper-world-path', default='world/paper-world.yml',
                   help='where it goes (26.x: world/dimensions/minecraft/overworld/paper-world.yml)')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    sys.path.insert(0, str(a.bench.resolve()))
    import realclient_probe as bench
    bench.W, bench.H = 480, 300  # a little larger than the AC bench: outlines must be visible on screenshots
    a.work.mkdir(parents=True, exist_ok=True)
    player = bench.PLAYER

    if a.mojang_cache:
        orig = bench.Server.__init__
        def init(self, work, paper, java, plugins):
            real = shutil.rmtree
            def keep(path, *x, **y):
                real(path, *x, **y)
                shutil.copytree(a.mojang_cache, work / 'server' / 'cache')
                if a.paper_world:
                    dest = work / 'server' / a.paper_world_path
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(a.paper_world, dest)
            shutil.rmtree = keep
            try:
                (work / 'server').mkdir(parents=True, exist_ok=True)
                orig(self, work, paper, java, plugins)
            finally:
                shutil.rmtree = real
        bench.Server.__init__ = init

    server = bench.Server(a.work, a.paper.resolve(), a.java, [a.jar] + a.plugin)
    if os.environ.get('AE_DEBUG'):
        cfg = server.dir / 'plugins' / 'SentinelAE'
        cfg.mkdir(parents=True, exist_ok=True)
        (cfg / 'config.yml').write_text('debug: true\n')
    server.pump('Done (', 300)
    client = bench.Client(a, server.port)
    bot = None
    results = []

    def check(name, ok, note=''):
        results.append(('PASS ' if ok else 'FAIL ') + name + ('' if ok else ': ' + note))
        print(results[-1], flush=True)

    def cmd(*cs):
        for c in cs:
            server.cmd(c)
        server.pump(seconds=1)

    try:
        # A closed stone room around the player; outside: chests, a barrel, a player, entities.
        cmd('forceload add -32 -32 32 32', 'setworldspawn 0 -60 0', 'gamerule spawnRadius 0', 'gamerule minecraft:spawn_radius 0',
            'gamerule spawn_radius 0')  # no player yet: the area must be loaded for fill/setblock
        server.pump(seconds=3)
        cmd('fill -6 -60 -6 6 -55 6 stone hollow', 'fill -5 -60 -5 5 -56 5 air',
            'setblock 0 -60 9 chest', 'setblock 2 -60 9 barrel', 'setblock -2 -60 9 shulker_box', 'setblock 3 -60 12 chest',
            'summon armor_stand -3 -60 10', 'summon oak_boat 4 -60 10', 'summon villager 0 -60 13 {NoAI:1b}')
        bot = subprocess.Popen(['node', str(a.target_bot), str(server.port), 'Target', a.bot_version],
                               env=dict(os.environ), stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        server.pump('Target joined the game', 60)
        cmd('tp Target 1.5 -60 11.5', 'gamemode survival Target')
        client.launch()
        server.pump(player + ' joined the game', 300)
        server.pump(seconds=12)
        client.center()
        cmd(f'gamemode survival {player}', f'tp {player} 0.5 -60 0.5 0 10')
        server.pump(seconds=4)
        modules = ('storage-esp', 'esp', 'tracers') if a.cheat == 'meteor' else ('StorageESP', 'ESP', 'Tracers')
        for m in modules:
            client.chat(f'.toggle {m} on' if a.cheat == 'meteor' else f'.toggle {m}')
        server.pump(seconds=4)
        client.screenshot(a.work / 'esp-closed-room.png')
        print('screenshot esp-closed-room.png (nothing outside the room may be outlined)', flush=True)

        # Window in the wall towards the chests: now they are in sight and ESP must draw them.
        cmd('fill -3 -60 6 3 -59 6 glass')  # a low window: the floor beyond the wall is in sight
        server.pump(seconds=4)
        client.screenshot(a.work / 'esp-window.png')
        print('screenshot esp-window.png (chests, barrel, player, stand, boat, villager outlined)', flush=True)

        cmd('fill -3 -60 6 3 -59 6 stone')
        # walk far away and wait: the revealed storage blocks must be hidden again
        cmd(f'tp {player} 0.5 -60 -40.5 0 10')
        server.pump(seconds=14)
        cmd(f'tp {player} 0.5 -60 0.5 0 10')
        server.pump(seconds=3)
        client.screenshot(a.work / 'esp-rehidden.png')
        print('screenshot esp-rehidden.png (storage outlines gone again)', flush=True)
        # walk around inside the room (real chunk/entity traffic), then leave through a door and look back
        client.hold('w', True); time.sleep(1.5); client.hold('w', False)
        cmd('fill 0 -60 6 0 -59 6 air', f'tp {player} 0.5 -60 4.5 0 0')
        server.pump(seconds=4)
        client.screenshot(a.work / 'esp-door.png')
        log = (a.work / 'client.log').read_text(errors='replace') if (a.work / 'client.log').exists() else ''
        # Real trouble only: a lost connection or a packet the client could not read (mod warnings are not it).
        keys = ('Disconnected', 'Lost connection', 'DecoderException', 'Failed to decode', 'Error loading chunk',
                'Bad packet', 'Packet error', 'kicked')
        bad = [l for l in log.splitlines() if any(k in l for k in keys)]
        check('real client loaded every chunk and stayed connected', client.alive() and not bad, '; '.join(bad[:3]))
        server.cmd('sae status')
        st = server.pump('storage ESP', 10) or ''
        print(st.strip()[:400], flush=True)
    finally:
        client.close()
        if bot:
            bot.kill()
        server.cmd('stop')
        try:
            server.process.wait(60)
        except Exception:
            server.process.kill()
        (a.work / 'server-output.log').write_text(''.join(server.log))
    print('DONE', sum(r.startswith('PASS') for r in results), '/', len(results))
    return 0


if __name__ == '__main__':
    sys.exit(main())
