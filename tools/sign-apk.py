#!/usr/bin/env python3
"""Align, sign and verify an APK using credentials kept outside the repository."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('apk',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--build-tools',type=Path,required=True)
parser.add_argument('--signing-dir',type=Path,required=True)
args=parser.parse_args()
credentials=json.loads((args.signing_dir/'credentials.json').read_text())
environment=dict(os.environ,RC_LOCAL_SIGN_PASSWORD=credentials['password'])
signer=['java','-jar',str(args.build_tools/'lib/apksigner.jar')]
args.output.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as temporary:
    aligned=Path(temporary)/'aligned.apk'
    subprocess.run([str(args.build_tools/'zipalign'),'-p','-f','4',str(args.apk),str(aligned)],check=True)
    subprocess.run(signer+['sign','--ks',str(args.signing_dir/'release.jks'),'--ks-key-alias',credentials['alias'],
        '--ks-pass','env:RC_LOCAL_SIGN_PASSWORD','--key-pass','env:RC_LOCAL_SIGN_PASSWORD',
        '--out',str(args.output),str(aligned)],env=environment,check=True)
subprocess.run(signer+['verify','--verbose','--print-certs',str(args.output)],check=True)
subprocess.run([str(args.build_tools/'zipalign'),'-c','-p','4',str(args.output)],check=True)
