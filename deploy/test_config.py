import copy
import unittest
from check_config import DATABASES, SERVICES, validate

class ConfigurationTest(unittest.TestCase):
    def setUp(self):
        database_secrets = {key: 'fixture-secret-' + str(index) for index, key in enumerate(DATABASES.values())}
        self.config = {'services': {name: {'image': 'ghcr.io/team/'+name+':abc123', 'environment': {
            'SPRING_DATASOURCE_URL': 'jdbc:postgresql://postgres:5432/test_db',
            'SPRING_DATASOURCE_USERNAME': 'app', 'SPRING_DATASOURCE_PASSWORD': database_secrets[key],
            'AUTH_JWT_ISSUER': 'https://test.example', 'AUTH_JWT_PRIVATE_KEY': 'test-key',
            'AUTH_JWT_KEY_ID': 'test-id', 'BOOKING_HOLD_SERVICE_TOKEN': 'x'*32}}
            for name, key in DATABASES.items()}}
        for name in SERVICES:
            self.config['services'].setdefault(name, {'image': 'ghcr.io/team/'+name+':abc123', 'environment': {
                'AUTH_JWT_ISSUER': 'https://test.example', 'BOOKING_HOLD_SERVICE_TOKEN': 'x'*32}})
        for name in DATABASES:
            if name != 'auth-service': self.config['services'][name]['environment'].pop('AUTH_JWT_PRIVATE_KEY', None)
        self.config['services'].update({name: {} for name in ('redis', 'kafka', 'nginx')})
        self.config['services']['postgres'] = {'environment': database_secrets}

    def test_complete_private_topology(self):
        self.assertTrue(validate(self.config))

    def test_internal_port_exposure_rejected(self):
        for name in (*SERVICES, 'postgres', 'redis', 'kafka'):
            with self.subTest(service=name):
                cfg=copy.deepcopy(self.config)
                cfg['services'][name]['ports']=[{'published': 5432}]
                with self.assertRaises(ValueError): validate(cfg)

    def test_missing_runtime_credentials_rejected(self):
        for name in ('AUTH_JWT_PRIVATE_KEY', 'AUTH_JWT_KEY_ID', 'BOOKING_HOLD_SERVICE_TOKEN'):
            cfg=copy.deepcopy(self.config)
            service='booking-service' if name=='BOOKING_HOLD_SERVICE_TOKEN' else 'auth-service'
            cfg['services'][service]['environment'][name]=''
            with self.assertRaises(ValueError): validate(cfg)

    def test_signing_key_cannot_leak_to_another_service(self):
        self.config['services']['payment-service']['environment']['AUTH_JWT_PRIVATE_KEY']='secret'
        with self.assertRaises(ValueError): validate(self.config)

    def test_mutable_image_rejected(self):
        self.config['services']['payment-service']['image']='team/payment:latest'
        with self.assertRaises(ValueError): validate(self.config)

    def test_http_issuer_rejected(self):
        self.config['services']['auth-service']['environment']['AUTH_JWT_ISSUER']='http://auth:8081'
        with self.assertRaises(ValueError): validate(self.config)

    def test_shared_host_database_rejected(self):
        self.config['services']['booking-service']['environment']['SPRING_DATASOURCE_URL']='jdbc:postgresql://localhost/booking'
        with self.assertRaises(ValueError): validate(self.config)
    def test_shared_database_password_rejected(self):
        auth_key = DATABASES['auth-service']
        booking_key = DATABASES['booking-service']
        shared = self.config['services']['postgres']['environment'][auth_key]
        self.config['services']['postgres']['environment'][booking_key] = shared
        self.config['services']['booking-service']['environment']['SPRING_DATASOURCE_PASSWORD'] = shared
        with self.assertRaises(ValueError): validate(self.config)

    def test_app_password_must_match_initializer(self):
        self.config['services']['booking-service']['environment']['SPRING_DATASOURCE_PASSWORD'] = 'different-secret'
        with self.assertRaises(ValueError): validate(self.config)

if __name__ == '__main__': unittest.main()
