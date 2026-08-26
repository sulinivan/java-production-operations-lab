#!/bin/sh
# Generate self-signed SSL certificates for localhost
echo "Generating self-signed SSL certificates for localhost..."
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout "$(dirname "$0")/localhost.key" \
  -out "$(dirname "$0")/localhost.crt" \
  -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"
echo "Certificates generated successfully: localhost.crt and localhost.key"
