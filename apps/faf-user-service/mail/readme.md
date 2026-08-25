Original mjml files to render are at https://github.com/FAForever/faf-user-service/tree/master/src/main/mjml

When copying over rendered .html files you need to
* replace all `{{variables}}` with ``{{`{{variables}}`}}``
  * `{{` to ``{{`{{``
  * `}}` to ``}}`}}``
* replace all domain mentions with `{{.Values.baseDomain}}`