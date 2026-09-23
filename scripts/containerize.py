#!/usr/bin/env python3
"""Discover standalone Gradle applications and assemble one runtime image context."""
import argparse
import json
from pathlib import Path
import re
import shlex
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.container-build'
IGNORE = {'.git', '.gradle', 'build', '.container-build', 'node_modules'}


def discover(root):
    apps = []
    for settings in sorted(root.rglob('settings.gradle*')):
        if any(part in IGNORE for part in settings.relative_to(root).parts):
            continue
        directory = settings.parent
        manifests = [p for p in [directory / 'build.gradle', directory / 'build.gradle.kts'] if p.is_file()]
        if len(manifests) != 1:
            raise ValueError(f'Expected one Gradle build in {directory}')
        text = manifests[0].read_text()
        if re.search(r'''id\s*(?:\(\s*)?['"]org\.springframework\.boot['"]''', text):
            kind, task = 'spring-boot', 'bootJar'
        elif re.search(r'''(?:id\s*(?:\(\s*)?['"]application['"]|^\s*application\s*$)''', text, re.M):
            kind, task = 'application', 'installDist'
        else:
            continue  # Library builds have no server entry point.
        versions = set(re.findall(r'JavaLanguageVersion\.of\((\d+)\)', text))
        if len(versions) != 1:
            raise ValueError(f'Cannot determine a single Java toolchain in {directory}')
        if not (directory / 'gradlew').is_file():
            raise ValueError(f'Missing Gradle wrapper in {directory}')
        apps.append({'path': directory.relative_to(root).as_posix(), 'kind': kind,
                     'task': task, 'java': int(versions.pop())})
    if not apps:
        raise ValueError('No runnable standalone Gradle applications discovered')
    if len({app['java'] for app in apps}) != 1:
        raise ValueError('Discovered applications require different Java versions')
    return apps


def assemble(apps, runtime):
    if OUTPUT.exists():
        raise ValueError('Use a clean checkout: .container-build already exists')
    OUTPUT.mkdir()
    commands = []
    for index, app in enumerate(apps):
        directory = ROOT / app['path']
        subprocess.run(['bash', './gradlew', '--no-daemon', 'test', app['task']], cwd=directory, check=True)
        destination = OUTPUT / 'apps' / str(index)
        destination.mkdir(parents=True)
        if app['kind'] == 'spring-boot':
            jars = [p for p in (directory / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
            if len(jars) != 1:
                raise ValueError(f'Expected one Boot jar in {directory}')
            shutil.copy2(jars[0], destination / 'app.jar')
            command = ['java', '-jar', f'/app/apps/{index}/app.jar']
        else:
            distributions = list((directory / 'build/install').glob('*'))
            if len(distributions) != 1:
                raise ValueError(f'Expected one application distribution in {directory}')
            shutil.copytree(distributions[0], destination, dirs_exist_ok=True)
            launchers = [p for p in (destination / 'bin').iterdir() if not p.name.endswith('.bat')]
            if len(launchers) != 1:
                raise ValueError(f'Expected one launcher in {directory}')
            launchers[0].chmod(0o755)
            command = [f'/app/apps/{index}/bin/{launchers[0].name}']
        args = runtime.get('arguments', {}).get(app['path'], [])
        if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
            raise ValueError('Runtime arguments must be a list of strings')
        commands.append(shlex.join(command + args))
    launcher = '''#!/bin/bash
set -uo pipefail
pids=()
stop_all() {
  trap '' TERM INT
  kill -TERM "${pids[@]}" 2>/dev/null || true
  for _ in {1..40}; do
    alive=0
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null && alive=1; done
    (( alive == 0 )) && break
    sleep 0.25
  done
  kill -KILL "${pids[@]}" 2>/dev/null || true
  wait || true
}
trap 'stop_all; exit 0' TERM INT
'''
    for command in commands:
        launcher += command + ' &\npids+=("$!")\n'
    launcher += '''wait -n
status=$?
stop_all
# Any unexpected child exit must make the combined container unhealthy.
(( status == 0 )) && status=1
exit "$status"
'''
    (OUTPUT / 'start.sh').write_text(launcher)
    ports = runtime.get('ports', [])
    if not ports or not all(isinstance(port, int) and 0 < port < 65536 for port in ports):
        raise ValueError('Configure service ports explicitly')
    (OUTPUT / 'Dockerfile').write_text(f'''FROM eclipse-temurin:{apps[0]['java']}-jre-jammy
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app \\
    && mkdir -p /app/data && chown -R app:app /app
WORKDIR /app
COPY --chown=app:app apps /app/apps
COPY --chmod=755 start.sh /app/start.sh
ENV SALAT_DATA_DIRECTORY=/app/data
USER 10001:10001
EXPOSE {' '.join(map(str, ports))}
ENTRYPOINT ["/app/start.sh"]
''')
    (OUTPUT / 'manifest.json').write_text(json.dumps(apps, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--java-version', action='store_true')
    args = parser.parse_args()
    apps = discover(ROOT)
    if args.java_version:
        print(apps[0]['java'])
        return
    print(json.dumps(apps, indent=2), flush=True)
    if args.build:
        assemble(apps, json.loads((ROOT / '.container-runtime.json').read_text()))


if __name__ == '__main__':
    main()
