"""Validate renderable Compose without printing secret-bearing configuration."""
import json
from pathlib import Path
import subprocess
import sys

DATABASES = {
    'auth-service': 'AUTH_DB_PASSWORD',
    'subscription-service': 'SUBSCRIPTION_DB_PASSWORD',
    'facility-service': 'FACILITY_DB_PASSWORD',
    'reporting-service': 'REPORTING_DB_PASSWORD',
    'discovery-service': 'DISCOVERY_DB_PASSWORD',
    'voucher-service': 'VOUCHER_DB_PASSWORD',
    'booking-service': 'BOOKING_DB_PASSWORD',
    'payment-service': 'PAYMENT_DB_PASSWORD',
    'staff-operations-service': 'STAFF_DB_PASSWORD',
    'pos-retail-service': 'POS_DB_PASSWORD',
    'notification-service': 'NOTIFICATION_DB_PASSWORD',
}
SERVICES = (*DATABASES, 'slot-hold-engine')


def validate(config):
    services = config['services']
    for name in (*SERVICES, 'postgres', 'redis', 'kafka', 'nginx'):
        if name not in services:
            raise ValueError('Missing service: ' + name)
    for name, service in services.items():
        if name != 'nginx' and service.get('ports'):
            raise ValueError('Internal ports published: ' + name)
    postgres_env = services['postgres'].get('environment', {})
    passwords = []
    for name in SERVICES:
        image = services[name]['image']
        tag = image.rsplit('/', 1)[-1]
        if ':' not in tag or tag.endswith(':latest') or 'REPLACE' in image:
            raise ValueError('Supply immutable release images: ' + name)
        env = services[name]['environment']
        if name != 'auth-service' and env.get('AUTH_JWT_PRIVATE_KEY'):
            raise ValueError('Signing key exposed outside auth-service')
        if name in DATABASES:
            if 'postgres:5432/' not in env.get('SPRING_DATASOURCE_URL', ''):
                raise ValueError('Missing private datasource: ' + name)
            username = env.get('SPRING_DATASOURCE_USERNAME')
            password = env.get('SPRING_DATASOURCE_PASSWORD', '')
            init_password = postgres_env.get(DATABASES[name], '')
            if not username or not password or 'REPLACE' in password:
                raise ValueError('Missing database setting: ' + name)
            if not init_password or 'REPLACE' in init_password:
                raise ValueError('Missing initializer database secret: ' + name)
            if password != init_password:
                raise ValueError('Application and initializer secrets differ: ' + name)
            passwords.append(password)
        if not env.get('AUTH_JWT_ISSUER', '').startswith('https://'):
            raise ValueError('Production issuer requires HTTPS')
    if len(set(passwords)) != len(passwords):
        raise ValueError('Database role passwords must be unique per service')
    auth = services['auth-service']['environment']
    for key in ('AUTH_JWT_PRIVATE_KEY', 'AUTH_JWT_KEY_ID'):
        if not auth.get(key) or 'REPLACE' in auth[key]:
            raise ValueError('Missing runtime secret: ' + key)
    token = services['booking-service']['environment'].get('BOOKING_HOLD_SERVICE_TOKEN', '')
    if len(token.encode()) < 32 or 'REPLACE' in token:
        raise ValueError('Service token must have at least 32 bytes')
    return True


if __name__ == '__main__':
    try:
        rendered = subprocess.run(['docker', 'compose', '--env-file', '.env', '--env-file', 'runtime.env', '-f', 'compose.yml', 'config', '--format', 'json'],
                                  cwd=Path(__file__).parent, capture_output=True, check=True)
        validate(json.loads(rendered.stdout))
    except (ValueError, KeyError, subprocess.CalledProcessError):
        print('Configuration invalid; inspect local configuration privately.', file=sys.stderr)
        sys.exit(2)
    print('Configuration structure passed. Image availability, TLS, cloud and live readiness remain unverified.')
