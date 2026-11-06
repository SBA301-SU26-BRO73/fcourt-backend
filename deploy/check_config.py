"""Validate renderable Compose without printing secret-bearing configuration."""
import json
from pathlib import Path
import subprocess
import sys

SERVICES = ('auth-service', 'subscription-service', 'facility-service', 'reporting-service',
            'discovery-service', 'voucher-service', 'slot-hold-engine', 'booking-service',
            'payment-service', 'staff-operations-service', 'pos-retail-service', 'notification-service')

def validate(config):
    services = config['services']
    for name in (*SERVICES, 'postgres', 'redis', 'kafka', 'nginx'):
        if name not in services:
            raise ValueError('Missing service: ' + name)
    for name, service in services.items():
        if name != 'nginx' and service.get('ports'):
            raise ValueError('Internal ports published: ' + name)
    for name in SERVICES:
        image = services[name]['image']
        tag = image.rsplit('/', 1)[-1]
        if ':' not in tag or tag.endswith(':latest') or 'REPLACE' in image:
            raise ValueError('Supply immutable release images: ' + name)
        env = services[name]['environment']
        if name != 'slot-hold-engine':
            if 'postgres:5432/' not in env.get('SPRING_DATASOURCE_URL', ''):
                raise ValueError('Missing private datasource: ' + name)
            for key in ('SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD'):
                if not env.get(key) or 'REPLACE' in env[key]:
                    raise ValueError('Missing database setting: ' + name)
        if not env.get('AUTH_JWT_ISSUER', '').startswith('https://'):
            raise ValueError('Production issuer requires HTTPS')
    auth = services['auth-service']['environment']
    for key in ('AUTH_JWT_PRIVATE_KEY', 'AUTH_JWT_KEY_ID', 'BOOKING_HOLD_SERVICE_TOKEN'):
        if not auth.get(key) or 'REPLACE' in auth[key]:
            raise ValueError('Missing runtime secret: ' + key)
    if len(auth['BOOKING_HOLD_SERVICE_TOKEN'].encode()) < 32:
        raise ValueError('Service token must have at least 32 bytes')
    return True

if __name__ == '__main__':
    try:
        rendered = subprocess.run(['docker', 'compose', '-f', 'compose.yml', 'config', '--format', 'json'],
                                  cwd=Path(__file__).parent, capture_output=True, check=True)
        validate(json.loads(rendered.stdout))
    except (ValueError, KeyError, subprocess.CalledProcessError):
        print('Configuration invalid; inspect local configuration privately.', file=sys.stderr)
        sys.exit(2)
    print('Configuration structure passed. Image availability, TLS, cloud and live readiness remain unverified.')
