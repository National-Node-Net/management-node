# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.

# Default values
WORKSPACE ?= dev
DIR ?= 01-global  # Change this based on the directory you want to work with

init:
	@cd $(DIR) && tofu init -backend-config=backends/$(WORKSPACE)-backend.tfvars
	@cd $(DIR) && tofu workspace select $(WORKSPACE) || tofu workspace new $(WORKSPACE)

plan:
	@cd $(DIR) && tofu plan -var-file=tfvars/$(WORKSPACE).tfvars -out=tfplan

apply:
	@cd $(DIR) && tofu apply tfplan

apply-auto-approve:
	@cd $(DIR) && tofu apply -auto-approve

destroy-plan:
	@cd $(DIR) && tofu plan -destroy -var-file=tfvars/$(WORKSPACE).tfvars

destroy:
	@cd $(DIR) && tofu destroy -var-file=tfvars/$(WORKSPACE).tfvars

format:
	tofu fmt --recursive

validate:
	@cd $(DIR) && tofu validate

pre-check:
	@cd $(DIR) && tofu fmt -check
	@cd $(DIR) && tofu validate

init-upgrade:
	@cd $(DIR) && rm -rf .terraform
	@cd $(DIR) && tofu init -upgrade -backend-config=backends/$(WORKSPACE)-backend.tfvars
	@cd $(DIR) && tofu workspace select $(WORKSPACE) || tofu workspace new $(WORKSPACE)

pre-commit: format validate
