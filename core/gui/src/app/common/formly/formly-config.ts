/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import { NullTypeComponent } from "./null.type";
import { ArrayTypeComponent } from "./array.type";
import { ObjectTypeComponent } from "./object.type";
import { MultiSchemaTypeComponent } from "./multischema.type";
import { FormlyFieldConfig } from "@ngx-formly/core";
import { CodeareaCustomTemplateComponent } from "../../workspace/component/codearea-custom-template/codearea-custom-template.component";
import { PresetWrapperComponent } from "./preset-wrapper/preset-wrapper.component";
import { InputAutoCompleteComponent } from "../../workspace/component/input-autocomplete/input-autocomplete.component";
import { CollabWrapperComponent } from "./collab-wrapper/collab-wrapper/collab-wrapper.component";
import { FormlyRepeatDndComponent } from "./repeat-dnd/repeat-dnd.component";

/**
 * Configuration for using Json Schema with Formly.
 * This config is copy-pasted from official documentation,
 * see https://formly.dev/examples/advanced/json-schema
 */
export const TEXERA_FORMLY_CONFIG = {
  validationMessages: [
    { name: "required", message: "This field is required" },
    { name: "null", message: "should be null" },
    { name: "minLength", message: minlengthValidationMessage },
    { name: "maxLength", message: maxlengthValidationMessage },
    { name: "min", message: minValidationMessage },
    { name: "max", message: maxValidationMessage },
    { name: "multipleOf", message: multipleOfValidationMessage },
    { name: "exclusiveMinimum", message: exclusiveMinimumValidationMessage },
    { name: "exclusiveMaximum", message: exclusiveMaximumValidationMessage },
    { name: "minItems", message: minItemsValidationMessage },
    { name: "maxItems", message: maxItemsValidationMessage },
    { name: "uniqueItems", message: "should NOT have duplicate items" },
    { name: "const", message: constValidationMessage },
  ],
  types: [
    { name: "string", extends: "input", defaultOptions: { defaultValue: "" } },
    {
      name: "number",
      extends: "input",
      defaultOptions: {
        templateOptions: {
          type: "number",
        },
      },
    },
    {
      name: "integer",
      extends: "input",
      defaultOptions: {
        templateOptions: {
          type: "number",
        },
      },
    },
    { name: "boolean", extends: "checkbox" },
    { name: "enum", extends: "select" },
    { name: "null", component: NullTypeComponent, wrappers: ["form-field"] },
    { name: "array", component: ArrayTypeComponent },
    { name: "object", component: ObjectTypeComponent },
    { name: "multischema", component: MultiSchemaTypeComponent },
    { name: "codearea", component: CodeareaCustomTemplateComponent },
    { name: "inputautocomplete", component: InputAutoCompleteComponent, wrappers: ["form-field"] },
    { name: "repeat-section-dnd", component: FormlyRepeatDndComponent },
  ],
  wrappers: [
    { name: "preset-wrapper", component: PresetWrapperComponent },
    { name: "collab-wrapper", component: CollabWrapperComponent },
  ],
};

export function minItemsValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should NOT have fewer than ${field.props?.minItems} items`;
}

export function maxItemsValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should NOT have more than ${field.props?.maxItems} items`;
}

export function minlengthValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should NOT be shorter than ${field.props?.minLength} characters`;
}

export function maxlengthValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should NOT be longer than ${field.props?.maxLength} characters`;
}

export function minValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be >= ${field.props?.min}`;
}

export function maxValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be <= ${field.props?.max}`;
}

export function multipleOfValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be multiple of ${field.props?.step}`;
}

export function exclusiveMinimumValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be > ${field.props?.exclusiveMinimum}`;
}

export function exclusiveMaximumValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be < ${field.props?.exclusiveMaximum}`;
}

export function constValidationMessage(err: any, field: FormlyFieldConfig) {
  return `should be equal to constant "${field.props?.const}"`;
}
