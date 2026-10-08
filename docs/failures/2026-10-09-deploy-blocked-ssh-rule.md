# Server deploy blocked: SSH rule points at an old IP

**Date:** 2026-10-09 · **Status:** open (needs the maintainer)

**What failed.**

```
$ WHISPR_HOST=wp-os.duckdns.org deploy/aws/deploy.sh 13.200.173.78 D:/whispr-release/whispr-ec2.pem
ssh: connect to host 13.200.173.78 port 22: Connection timed out
host not ready (cloud-init still running?)
```

**Cause.** The `whispr-sg` security group allows SSH only from
`223.228.137.237/32`; the maintainer's address is now `106.192.114.104`.
Changing the rule was refused by the assistant's permission policy (a
security-group change), so it was left to the maintainer.

**State.** The new server image (TURN credentials) is built
(`server-image` run for `ed9b2d1`) and the TURN ports (UDP/TCP 3478, UDP
49160–49200) are already open. The live server still runs the previous
build, so `GET /v1/calls/turn` answers 404 and calls have no relay.

**To finish.**

```sh
aws ec2 authorize-security-group-ingress --group-id sg-0d6f118919c7c60fe \
  --ip-permissions 'IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=106.192.114.104/32,Description="maintainer"}]'
aws ec2 revoke-security-group-ingress --group-id sg-0d6f118919c7c60fe \
  --ip-permissions 'IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=223.228.137.237/32}]'
WHISPR_HOST=wp-os.duckdns.org deploy/aws/deploy.sh 13.200.173.78 D:/whispr-release/whispr-ec2.pem
```
