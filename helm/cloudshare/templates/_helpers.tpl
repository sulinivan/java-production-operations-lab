{{/*
Метки по умолчанию для всех ресурсов чарта.
*/}}
{{- define "cloudshare.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: java-production-operations-lab
{{- end }}

{{/*
Имя основного ресурса (deployment/service/configmap).
*/}}
{{- define "cloudshare.fullname" -}}
{{ .Release.Name }}
{{- end }}
