#!/usr/bin/env python3
"""Live SentinelAE test (localhost only): a disposable Paper server, a viewer bot that decodes every chunk and logs
spawns, sounds, particles and block-break animations, and a target bot. Checks storage ESP hiding (chunk data and
block updates), entity concealment for every protected group, and the sound/particle filter.

  NODE_PATH=<lab>/node-ae/node_modules python3 tools/ae_probe.py --paper paper.jar --java java --jar SentinelAE.jar \
      [--via ViaVersion.jar --via ViaBackwards.jar] --mojang-cache dir --work dir --client-version 1.21.11 --accept-eula
"""
import argparse, json, queue, shutil, socket, subprocess, sys, threading, time
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'client' / 'viewer.cjs'


class Proc:
    def __init__(self, args, cwd=None, stdin=True):
        self.p = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE if stdin else None, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.q, self.log = queue.Queue(), []
        threading.Thread(target=lambda: [self.q.put(l) for l in self.p.stdout], daemon=True).start()

    def send(self, t):
        self.p.stdin.write(t + '\n'); self.p.stdin.flush()

    def pump(self, until=None, seconds=0.0):
        end = time.monotonic() + seconds
        while True:
            try:
                l = self.q.get(timeout=0.1)
                self.log.append(l)
                if until and until in l:
                    return l
            except queue.Empty:
                pass
            if time.monotonic() >= end:
                if until:
                    raise RuntimeError('no "' + until + '"')
                return None


def events(bot, since):
    bot.pump(seconds=0.3)  # move everything the bot printed so far into its log
    out = []
    for l in bot.log[since:]:
        try:
            out.append(json.loads(l))
        except ValueError:
            pass
    return out


def main():
    p = argparse.ArgumentParser()
    for k in ('--paper', '--jar', '--work'):
        p.add_argument(k, type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--client-version', default='1.21.11')
    p.add_argument('--boat', default='oak_boat')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('accept the Minecraft EULA first')
    srv_dir = a.work / 'server'
    if srv_dir.exists():
        shutil.rmtree(srv_dir)
    (srv_dir / 'plugins').mkdir(parents=True)
    for jar in [a.jar] + a.via:
        shutil.copy2(jar, srv_dir / 'plugins' / jar.name)
    if a.mojang_cache:
        shutil.copytree(a.mojang_cache, srv_dir / 'cache')
    (srv_dir / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
    (srv_dir / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
        'max-players=5\nview-distance=4\nsimulation-distance=4\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
        'spawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nspawn-animals=false\n')
    server = Proc([a.java, '-Xms256M', '-Xmx900M', '-jar', str(a.paper.resolve()), '--nogui'], cwd=srv_dir)
    results, viewer, target = [], None, None

    def check(name, ok, detail=''):
        results.append(('PASS ' if ok else 'FAIL ') + name + ('' if ok else ': ' + detail))
        print(results[-1], flush=True)

    def query(x, y, z):
        mark = len(viewer.log)
        viewer.send(f'query {x} {y} {z}')
        viewer.pump('"ev":"query"', 10)
        for e in events(viewer, mark):
            if e.get('ev') == 'query':
                return e.get('block')

    try:
        server.pump('Done (', 300)
        # built before anyone joins: the very first chunks must already be filtered
        for c in ('fill 10 -60 20 20 -54 20 stone', 'setblock 15 -60 24 chest'):
            server.send(c)
        server.pump(seconds=2)
        viewer = Proc(['node', str(BOT), str(port), 'Viewer', a.client_version])
        server.pump('Viewer joined the game', 60)
        target = Proc(['node', str(BOT), str(port), 'Target', a.client_version])
        server.pump('Target joined the game', 60)
        target_id = None
        target.pump('"ev":"login"', 20)
        for e in events(target, 0):
            if e.get('ev') == 'login':
                target_id = e['entityId']
        for c in ('gamemode survival Viewer', 'gamemode survival Target', 'tp Target -20 -60 -20',
                  'tp Viewer 0.5 -60 0.5 0 0', 'fill -8 -60 6 8 -54 6 stone',
                  'setblock 0 -60 10 chest', 'setblock 2 -60 10 barrel', 'setblock -3 -60 3 chest'):
            server.send(c)
        server.pump(seconds=2)
        # fresh chunk sends: away and back
        server.pump(seconds=2)
        j = query(15, -60, 24)
        check('join: chest behind wall in the first chunks arrives as air', j == 'air', str(j))
        server.send('tp Viewer 3000.5 -60 3000.5'); server.pump(seconds=8)
        server.send('tp Viewer 0.5 -60 0.5 0 0'); server.pump(seconds=6)
        errors = [e for e in events(viewer, 0) if e.get('ev') in ('chunk-error', 'error', 'end')]
        check('chunks-decode', not errors, str(errors[:3]))
        a_, b_, c_ = query(0, -60, 10), query(2, -60, 10), query(-3, -60, 3)
        check('chunk: chest behind wall arrives as air', a_ == 'air', str(a_))
        check('chunk: barrel behind wall arrives as stone', b_ == 'stone', str(b_))
        check('chunk: chest in sight is revealed', c_ == 'chest', str(c_))

        mark = len(viewer.log)
        server.send('fill -8 -60 6 8 -54 6 air'); server.pump(seconds=3)
        a_, b_ = query(0, -60, 10), query(2, -60, 10)
        check('wall removed: chest revealed', a_ == 'chest', str(a_))
        check('wall removed: barrel revealed', b_ == 'barrel', str(b_))

        server.send('fill -8 -60 6 8 -54 6 stone'); server.pump(seconds=2)
        server.send('setblock 4 -60 10 chest'); server.pump(seconds=3)
        d_ = query(4, -60, 10)
        check('new chest behind wall not sent', d_ != 'chest', str(d_))

        mark = len(viewer.log)
        for c in ('summon armor_stand 0 -60 12', f'summon {a.boat} 2 -60 12',
                  'summon item 4 -60 12 {Item:{id:"minecraft:diamond",count:1},Age:-32768}',
                  'summon villager -2 -60 12 {NoAI:1b}', 'summon wolf -4 -60 12 {NoAI:1b}',
                  'summon arrow -6 -60 12 {NoGravity:1b}', 'summon armor_stand -3 -60 2'):
            server.send(c)
        server.send('tp Target 0.5 -60 11'); server.pump(seconds=4)
        spawns = [e for e in events(viewer, mark) if e.get('ev') == 'spawn']
        behind = [e for e in spawns if e.get('z', 0) > 7]
        front = [e for e in spawns if e.get('z', 0) < 5 and e.get('type') == 'armor_stand']
        check('entities behind wall not sent', not behind, str(behind))
        check('entity in sight sent', bool(front), str(spawns))

        mark = len(viewer.log)
        server.send('playsound minecraft:entity.generic.hurt player Viewer 0.5 -60 11 1 1')
        server.send('particle minecraft:crit 0.5 -59 11 0 0 0 0 5 force Viewer')
        server.pump(seconds=1)
        target.send('dig 1 -61 11'); server.pump(seconds=2)
        server.send('playsound minecraft:entity.generic.hurt player Viewer -3 -60 2 1 1')
        server.send('particle minecraft:crit -3 -59 2 0 0 0 0 5 force Viewer')
        server.pump(seconds=2)
        ev = events(viewer, mark)
        hidden_sounds = [e for e in ev if e.get('ev') == 'sound' and e['z'] > 7]
        hidden_particles = [e for e in ev if e.get('ev') == 'particle' and e['z'] > 7]
        anims = [e for e in ev if e.get('ev') == 'break-anim' and e.get('id') == target_id]
        check('sound at concealed player dropped', not hidden_sounds, str(hidden_sounds))
        check('particles at concealed player dropped', not hidden_particles, str(hidden_particles))
        check('break animation of concealed player dropped', not anims, str(anims))
        check('control sound delivered', any(e.get('ev') == 'sound' and e['z'] < 5 for e in ev), str(ev[-5:]))
        check('control particles delivered', any(e.get('ev') == 'particle' and e['z'] < 5 for e in ev), '')

        mark = len(viewer.log)
        server.send('fill -8 -60 6 8 -54 6 air'); server.pump(seconds=3)
        target.send('dig 1 -61 11'); server.pump(seconds=2)
        ev = events(viewer, mark)
        spawns = [e for e in ev if e.get('ev') == 'spawn']
        kinds = sorted({e.get('type') for e in spawns})
        check('control: break animation of a visible player delivered',
              any(e.get('ev') == 'break-anim' and e.get('id') == target_id for e in ev), '')
        check('wall removed: player and entities appear', target_id in [e.get('id') for e in spawns] and len(kinds) >= 4, str(kinds))
        server.send('sae status'); server.pump(seconds=1)
        st = [l for l in server.log if 'storage ESP' in l]
        print((st[-1].strip() if st else 'no status')[:400])
    finally:
        for b in (viewer, target):
            if b:
                b.p.kill()
        try:
            server.send('stop'); server.p.wait(60)
        except Exception:
            server.p.kill()
        a.work.mkdir(parents=True, exist_ok=True)
        (a.work / 'server-output.log').write_text(''.join(server.log))
        if viewer:
            (a.work / 'viewer.log').write_text(''.join(viewer.log))
    print('DONE', sum(r.startswith('PASS') for r in results), '/', len(results))
    return 0 if results and all(r.startswith('PASS') for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
