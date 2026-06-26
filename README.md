# psu-file-expander

Проект для анализа и последующего расширения XML-файлов, которые загружаются
в корпоративный сервис `psu-sap-uploader`, а затем через сервис `profile`
попадают в PostgreSQL.

## Анализ XML

Сначала нужно запустить анализатор на исходном XML-файле и передать полученный
текстовый отчет для следующего шага разработки:

```bash
scripts/analyze_xml.sh /path/to/source.xml > xml-analysis.txt
```

Если скрипт запускается из каталога `scripts`, используйте:

```bash
./analyze_xml.sh /path/to/source.xml > xml-analysis.txt
```

Или явно через `bash`:

```bash
bash scripts/analyze_xml.sh /path/to/source.xml > xml-analysis.txt
```

Анализатор не печатает текстовые значения из XML. В отчете будут только
структура файла, повторяющиеся пути, длины полей, предполагаемые типы значений
и выборочная оценка уникальности полей.
