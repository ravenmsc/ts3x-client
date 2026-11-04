#!/usr/bin/env python3
"""Run client operations against a disposable, loopback-only TS3 or TS6 server."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request


def free_port(kind=socket.SOCK_STREAM, exclude=()):
    # Choose distinct, low loopback ports for the disposable server.
    for port in range(19000, 32000):
        if port in exclude:
            continue
        try:
            with socket.socket(socket.AF_INET, kind) as sock:
                sock.bind(('127.0.0.1', port))
                return port
        except OSError:
            continue
    raise RuntimeError('No free loopback port in the test range')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-dir', required=True, type=Path, help='Extracted official server distribution')
    parser.add_argument('--generation', choices=['ts3', 'ts6'], required=True)
    parser.add_argument('--accept-server-license', action='store_true', help='Accept the LICENSE in the supplied distribution')
    parser.add_argument('--s3-profile', action='store_true', help='Announce S3 on TS6; test system files only, without an S3 bucket')
    parser.add_argument('--password-profile', action='store_true', help='Use a protected server with a Unicode test password')
    parser.add_argument('--gradle', type=Path, help='Optional installed Gradle executable instead of the wrapper')
    parser.add_argument('--reordering-profile', action='store_true', help='Run the delayed-packet regression on a fresh isolated server')
    parser.add_argument('--startup-profile', action='store_true', help='Test readiness during a delayed subscription on a fresh server')
    args = parser.parse_args()
    if args.startup_profile and args.reordering_profile:
        parser.error('Choose one integration profile at a time')
    if not args.accept_server_license:
        parser.error('Read the server LICENSE and supply --accept-server-license before running the test server.')
    if args.s3_profile and args.generation != 'ts6':
        parser.error('--s3-profile requires --generation ts6')
    root = Path(__file__).resolve().parent.parent
    source = args.server_dir.resolve()
    base = 'tsserver' if args.generation == 'ts6' else 'ts3server'
    executable = base + ('.exe' if os.name == 'nt' else '')
    if not (source / executable).is_file():
        parser.error(f'{executable} not found in --server-dir')
    label = args.generation + ('-s3' if args.s3_profile else '') + ('-password' if args.password_profile else '') + ('-reordering' if args.reordering_profile else '') + ('-startup' if args.startup_profile else '')
    report = root / 'protocol' / 'build' / 'live-reports' / label
    report.mkdir(parents=True, exist_ok=True)
    voice_port = free_port(socket.SOCK_DGRAM)
    file_port = free_port(exclude=(voice_port,))
    query_port = free_port(exclude=(voice_port, file_port))
    # The original distribution and any existing server database are never edited.
    with tempfile.TemporaryDirectory(prefix='ts3x-protocol-') as temp:
        directory = Path(temp)
        for item in source.iterdir():
            if item.is_file() and (item.name in (executable, 'LICENSE') or '.so' in item.name or item.suffix.lower() == '.dll'):
                shutil.copy2(item, directory / item.name)
            elif item.name in ('sql', 'serverquerydocs'):
                shutil.copytree(item, directory / item.name)
        # A shell used to administer a real server can contain database/config
        # environment variables. Never inherit those into the disposable server.
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('TSSERVER_', 'TS3SERVER_'))}
        environment['TS3SERVER_LICENSE'] = 'accept'
        if args.generation == 'ts6':
            command = [str(directory / executable), '--accept-license=accept', '--voice-ip=127.0.0.1',
                       '--filetransfer-ip=127.0.0.1', f'--default-voice-port={voice_port}',
                       f'--filetransfer-port={file_port}', '--query-http-enable=1', f'--query-http-port={query_port}',
                       '--query-http-ip=127.0.0.1', '--query-admin-api-key=ts3x-isolated-test', '--query-ssh-enable=0']
            if args.s3_profile:
                command += ['--s3ft-enabled=1', '--s3ft-domain=http://127.0.0.1:19000', '--s3ft-bucket=ts3x-test',
                            '--s3ft-region=us-east-1', '--s3ft-access-key=local-test', '--s3ft-secret-key=local-test']
        else:
            command = [str(directory / executable), 'voice_ip=127.0.0.1', 'filetransfer_ip=127.0.0.1',
                       f'default_voice_port={voice_port}', f'filetransfer_port={file_port}',
                       'query_ip=127.0.0.1', f'query_port={query_port}', 'query_protocols=raw',
                       'serveradmin_password=ts3x-isolated-test']
        with (report / 'server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT)
            raw_socket = None
            try:
                for _ in range(150):
                    text = (report / 'server.log').read_text(encoding='utf-8', errors='replace')
                    if process.poll() is not None:
                        raise RuntimeError('Test server exited. See ' + str(report / 'server.log'))
                    if f'listening on 127.0.0.1:{voice_port}' in text:
                        break
                    time.sleep(0.1)
                else:
                    raise RuntimeError('Test server startup timed out')
                if args.generation == 'ts6':
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                    def query(command_name, **parameters):
                        request = urllib.request.Request(
                            f'http://127.0.0.1:{query_port}/1/' + command_name + '?' + urllib.parse.urlencode(parameters),
                            headers={'x-api-key': 'ts3x-isolated-test'})
                        result = json.load(opener.open(request, timeout=5))
                        if result['status']['code'] != 0:
                            raise RuntimeError((command_name, result['status']))
                        body = result.get('body') or []
                        return body if isinstance(body, list) else [body]
                else:
                    # The voice listener can appear in the log before ServerQuery is ready.
                    for attempt in range(50):
                        try:
                            raw_socket = socket.create_connection(('127.0.0.1', query_port), timeout=1)
                            break
                        except (ConnectionRefusedError, TimeoutError):
                            if process.poll() is not None or attempt == 49:
                                raise
                            time.sleep(0.1)
                    stream = raw_socket.makefile('r', encoding='utf-8')

                    def query(command_name, **parameters):
                        def escape(value):
                            return str(value).replace('\\', '\\\\').replace(' ', '\\s').replace('|', '\\p').replace('/', '\\/')
                        request = command_name + ''.join(' ' + key + '=' + escape(value) for key, value in parameters.items())
                        raw_socket.sendall((request + '\n').encode())
                        payload = []
                        while True:
                            line = stream.readline()
                            if not line:
                                raise RuntimeError('Test ServerQuery disconnected')
                            line = line.strip()
                            if line.startswith('error '):
                                if not line.startswith('error id=0 '):
                                    raise RuntimeError((command_name, line))
                                break
                            if line and '=' in line and not line.startswith('Welcome'):
                                payload.append(line)
                        return [dict(pair.split('=', 1) for pair in row.split(' ') if '=' in pair)
                                for row in '|'.join(payload).split('|') if row]

                    query('login', client_login_name='serveradmin', client_login_password='ts3x-isolated-test')
                    query('use', sid=1)
                groups = query('servergrouplist')
                admin_group = next(group['sgid'] for group in groups
                                   if group.get('name') in ('Server Admin', 'Server\\sAdmin') and str(group.get('type')) == '1')
                token = query('privilegekeyadd', tokentype=0, tokenid1=admin_group, tokenid2=0)[0]['token']
                server_group = query('servergroupadd', name='TS3X test role', type=1)[0]['sgid']
                channel_group = query('channelgroupadd', name='TS3X test role', type=1)[0]['cgid']
                for permission in ['i_group_needed_member_add_power', 'i_group_needed_member_remove_power']:
                    query('servergroupaddperm', sgid=server_group, permsid=permission, permvalue=0, permnegated=0, permskip=0)
                    query('channelgroupaddperm', cgid=channel_group, permsid=permission, permvalue=0)
                default_group = query('serverinfo')[0]['virtualserver_default_channel_group']
                query('channelgrouprename', cgid=default_group, name='TS3X renamed default')
                query('serveredit', virtualserver_antiflood_points_needed_command_block=10000,
                      virtualserver_antiflood_points_needed_ip_block=20000)
                server_password = 'TS3X test / пароль' if args.password_profile else ''
                if server_password:
                    query('serveredit', virtualserver_password=server_password)
                    if str(query('serverinfo')[0].get('virtualserver_flag_password')) != '1':
                        raise RuntimeError('Test server did not enable its password')
                if args.gradle:
                    runner = [str(args.gradle.resolve())]
                elif os.name == 'nt':
                    runner = [str(root / 'gradlew.bat')]
                else:
                    runner = ['bash', str(root / 'gradlew')]
                runner += ['--no-daemon', ':protocol:test', '--tests',
                           ('com.morsen.ts3xclient.protocol.LiveStartupTest' if args.startup_profile else
                            'com.morsen.ts3xclient.protocol.LivePacketReorderingTest' if args.reordering_profile else
                            'com.morsen.ts3xclient.protocol.LiveServerCompatibilityTest'), '--rerun-tasks', '--console=plain',
                           f'-Dts3x.test.port={voice_port}', f'-Dts3x.test.generation={args.generation.upper()}',
                           f'-Dts3x.test.token={token}', f'-Dts3x.test.servergroup={server_group}',
                           f'-Dts3x.test.channelgroup={channel_group}', f'-Dts3x.test.password={server_password}']
                print(f'Running {label} integration test on loopback; reports: {report}', flush=True)
                with (report / 'client.log').open('w', encoding='utf-8') as output:
                    result = subprocess.run(runner, cwd=root, env=environment, stdout=output,
                                            stderr=subprocess.STDOUT, timeout=600)
                print((report / 'client.log').read_text(encoding='utf-8', errors='replace')[-6000:])
                return result.returncode
            finally:
                if raw_socket is not None:
                    raw_socket.close()
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == '__main__':
    raise SystemExit(main())
