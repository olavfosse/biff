#!/bin/bash
source secrets.env
export BIFF_ENV=dev
clj -X com.example/-dev-main
