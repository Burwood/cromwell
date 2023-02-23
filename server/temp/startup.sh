#! /usr/bin/bash
apt update
apt -y install apache2
ZONE=`curl -fs http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google" | cut -d'/' -f4`
cat <<EOF > /var/www/html/index.html
<html><body><h1>Hello Cloud Gurus</h1>
<p>This server is serving from ${ZONE}.</p>
</body></html>
EOF
