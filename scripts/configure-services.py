"""Write build properties without logging registration values."""
import os
import sys
from pathlib import Path

def escape(value):
    return value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('=', '\\=').replace(':', '\\:').replace(' ', '\\ ')

keys = ['TRAKT_CLIENT_ID', 'TRAKT_CLIENT_SECRET', 'TRAKT_REDIRECT_URI', 'TMDB_API_KEY', 'SIMKL_CLIENT_ID', 'NUVIO_SUPABASE_URL', 'NUVIO_SUPABASE_ANON_KEY', 'NUVIO_SUPABASE_FALLBACK_URL']
if os.environ.get('REQUIRE_REGISTRATIONS') == 'true':
    if any(not os.environ.get(k) for k in ['TRAKT_CLIENT_ID', 'SIMKL_CLIENT_ID', 'TMDB_API_KEY']):
        raise SystemExit('Missing TV repository service secrets. Reuse the mobile registrations; do not create new ones.')
values = {k: os.environ[k] for k in keys if os.environ.get(k)}
# Public client configuration published by NuvioMedia/self-host/.env.example.
# This is a publishable key, never a service-role key. Explicit build values win.
values.setdefault('NUVIO_SUPABASE_URL', "https://api.nuvio.tv")
values.setdefault('NUVIO_SUPABASE_ANON_KEY', "sb_publishable_1Clq8rlTVACkdcZuqr6_AD__xUUC_EN")
Path(sys.argv[1]).write_text(''.join(k + '=' + escape(v) + '\n' for k, v in values.items()))
Path(sys.argv[1]).chmod(0o600)
print('Service properties configured; authentication requires populated repository secrets.')
