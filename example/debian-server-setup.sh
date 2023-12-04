#!/usr/bin/env bash
set -x
set -e

BIFF_ENV=${1:-prod}
CLJ_VERSION=1.11.1.1165
TRENCH_VERSION=0.4.0
# TODO: parameterize on ARCH
ARCH=arm64
TRENCH_FILE=trenchman_${TRENCH_VERSION}_linux_${ARCH}.tar.gz

echo waiting for apt to finish
while (ps aux | grep [a]pt); do
  echo "Waiting 3 seconds for apt-lock to be released"
  sleep 3
done

# Dependencies
apt-get update
apt-get upgrade
apt-get -y install default-jre rlwrap ufw git snapd
bash < <(curl -s https://download.clojure.org/install/linux-install-$CLJ_VERSION.sh)
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
wget https://github.com/athos/trenchman/releases/download/v$TRENCH_VERSION/$TRENCH_FILE
mkdir .trench_tmp
tar -xf $TRENCH_FILE --directory .trench_tmp
mv .trench_tmp/trench /usr/local/bin/
rm -rf $TRENCH_FILE .trench_tmp

# Non-root user
useradd -m app
mkdir -m 700 -p /home/app/.ssh
cp /root/.ssh/authorized_keys /home/app/.ssh
chown -R app:app /home/app/.ssh

# Git deploys
set_up_app () {
  cd
  mkdir repo.git
  cd repo.git
  git init --bare
  cat > hooks/post-receive << EOD
#!/usr/bin/env bash
git --work-tree=/home/app/tree --git-dir=/home/app/repo.git checkout -f
EOD
  chmod +x hooks/post-receive
}
sudo -u app bash -c "$(declare -f set_up_app); set_up_app"

# Systemd service
cat > /etc/systemd/system/app.service << EOD
[Unit]
Description=app
StartLimitIntervalSec=500
StartLimitBurst=5

[Service]
User=app
Restart=on-failure
RestartSec=5s
Environment="BIFF_ENV=$BIFF_ENV"
WorkingDirectory=/home/app/tree
ExecStart=/bin/sh -c '\$\$(bb run-cmd)'

[Install]
WantedBy=multi-user.target
EOD
systemctl enable app
cat > /etc/systemd/journald.conf << EOD
[Journal]
Storage=persistent
EOD
systemctl restart systemd-journald
cat > /etc/sudoers.d/restart-app << EOD
app ALL= NOPASSWD: /bin/systemctl reset-failed app.service
app ALL= NOPASSWD: /bin/systemctl restart app
app ALL= NOPASSWD: /usr/bin/systemctl reset-failed app.service
app ALL= NOPASSWD: /usr/bin/systemctl restart app
EOD
chmod 440 /etc/sudoers.d/restart-app

# Web dependencies
# https://caddyserver.com/docs/install#debian-ubuntu-raspbian
apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
apt -y update
apt -y install caddy

# Caddy
cat > /etc/caddy/Caddyfile << EOD
greenskill.io {
    # HTTPS is automatic!
    # https://caddyserver.com/docs/automatic-https
    root * /home/app/tree/target/resources/public
    file_server
    reverse_proxy localhost:8080
}
EOD

# App dependencies
# If you need to install additional packages for your app, you can do it here.
# apt-get -y install ...
