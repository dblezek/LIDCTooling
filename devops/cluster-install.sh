#!/bin/sh

source sc-env/bin/activate
cluster_meta="$(starcluster listclusters $1 2>&1)"
# MASTER=$(echo "${cluster_meta}" | grep "master running" | sed -e 's/.*master.*\(ec2-.*com\)/\1/g')

MASTER=$(echo "${cluster_meta}" | grep "master running" | awk '{print $4}')

ssh-add  ~/.ssh/mykey.rsa
ssh-add  ~/.ssh/radiomics.rsa
rsync -arv --exclude "*.a" ClusterSoftware/ root@$MASTER:/software/
rsync -arv devops/*.sh sgeadmin@$MASTER:
