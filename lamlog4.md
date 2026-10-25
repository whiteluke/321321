# Лабораторная работа: Обработка текстовых данных и построение базы знаний в Prolog (Вариант 14)
* Вариант: 14 (Нормализация дат).
* Формат входного файла (`input.txt`): текстовый файл в кодировке UTF-8, где каждая запись располагается на отдельной строке и состоит из 4 полей, разделённых вертикальной чертой `|`:
  `DocID | Category | Priority | Text`
  Пустые строки и строки-комментарии (начинающиеся с символа `#`) игнорируются загрузчиком. Текстовое поле содержит описания событий с датами в пяти различных форматах (`05.07.2026`, `2026-07-05`, `5 июля 2026`, `07/05/2026`, `5 июл. 2026 г.`), а также календарно некорректные даты и нестандартные текстовые фрагменты.
* Структура термов в динамической базе знаний:
  * `record(DocID, Category, Priority, Text)` — очищенная и проверенная запись документа.
  * `rejected(LineNo, RawLine, Reason)` — запись журнала отклонённых строк с номером строки, исходным текстом и термом причины ошибки.
  * `date(Year, Month, Day)` — каноническое внутреннее представление нормализованной даты.

---

## 1. Фрагмент входного файла с отмеченными грязными строками и видом дефекта каждой

Входной файл содержит 36 строк (включая комментарии, пустые строки, корректные записи и весь набор дефектов). Ниже приведены строки с дефектами и способы их обработки:

| № строки | Содержимое строки во входном файле | Вид дефекта | 
| :--- | :--- | :--- | 
| 1, 27 | `# Журнал документов...` и пустые строки | Пустые строки и строки-комментарии |
| **2–6, 12** | `Meeting`, `RELEASE`, `meeting`, `CONTRACT` | Разный регистр одного и того же значения |
| **7** | `   DOC-006   \|   contract   \|   5   \|   Подписание...   ` | Лишние пробелы в начале, конце и между полями и словами | 
| **28** | `DOC-027 \|  \| 3 \| Запись с пустым полем...` | Пустое поле (два разделителя подряд) |
| **29** | `DOC-028 \| meeting \| 4` | Неверное число полей в строке (3 вместо 4) |
| **30** | `DOC-029 \| report \| 3a \| Запись с буквой...` | Нечисловое значение в числовом поле (`3a`) |
| **31** | `DOC-030 \| audit \| — \| Запись с тире...` | Нечисловое значение в числовом поле (тире `—`) |
| **32** | `DOC-031 \| report \|  \| Запись с пустой строкой...` | Нечисловое значение в числовом поле (пустое поле) | 
| **33, 34** | `DOC-032 \| audit \| 7 \| ...` и `DOC-033 \| finance \| -2 \| ...` | Значение вне допустимого диапазона (`7` и `-2` при шкале `1..5`) | 
| **35** | `BAD-999 \| meeting \| 3 \| Запись с нарушением...` | Нарушение формата кода документа | 

---

## 2. Таблица «Поле — Тип» и обоснование выбора

| Поле / Аргумент | Имя в терме | Тип в базе знаний | Обоснование выбора |
| :--- | :--- | :--- | :--- |
| Идентификатор документа | `DocID` | `atom` | Короткий фиксированный код (`'DOC-001'`). Интернирование атомов позволяет мгновенно сопоставлять идентификаторы по указателю при индексации и объединении запросов. |
| Категория события | `Category` | `atom` | Ограниченное множество повторяющихся меток (`'meeting'`, `'release'`, `'audit'`, `'finance'`). Хранение в таблице атомов экономит память, каждая категория хранится в единственном экземпляре и ускоряет группировку. |
| Приоритет | `Priority` | `integer` | Целое число от `1` до `5`. Необходимо для арифметических сравнений, фильтрации диапазонов (`between/3`) и числовой агрегации. |
| Текст документа | `Text` | `string` | Произвольный длинный текст. Хранение таких текстов в виде атомов привело бы к засорению глобальной таблицы атомов без выгоды при унификации. Строки (`string`) размещаются в стеке и эффективно обрабатываются регулярными выражениями `library(pcre)`. |
| Тип формата даты | `FormatType` | `atom` | Перечислимый системный идентификатор шаблона (`dmy_dot`, `iso_dash`, `ru_full`, `mdy_slash`, `ru_abbr`). |
| Год, Месяц, День | `Y, M, D` в `date/3` | `integer` | Числовые компоненты даты, необходимые для проверки високосного года и вычисления числового ключа сортировки `Y*10000 + M*100 + D`. |
| ISO-дата | `IsoString` | `string` | Отформатированное текстовое представление `"YYYY-MM-DD"` для вывода и сериализации в JSON. |

---

## 3. Код `parse_line/2`

### Листинг `parse_line/2`:
```prolog
validate_doc_id(IdStr, DocID) :-
    re_match("^DOC-\\d{3}$", IdStr),
    atom_string(DocID, IdStr),
    sub_atom(DocID, 0, 4, 3, 'DOC-').

parse_line(Line, record(DocID, Category, Priority, CleanText)) :-
    split_string(Line, "|", " \t", Fields),
    (   Fields = [IdStr, CatStr, PrioStr, RawText]
    ->  true
    ;   length(Fields, N),
        throw(parse_error(field_count(N)))
    ),
    (   IdStr \== "", validate_doc_id(IdStr, DocID)
    ->  true
    ;   throw(parse_error(bad_doc_id(IdStr)))
    ),
    (   CatStr \== ""
    ->  string_lower(CatStr, CatLower),
        atom_string(Category, CatLower)
    ;   throw(parse_error(empty_category))
    ),
    (   number_string(Priority, PrioStr),
        integer(Priority)
    ->  (   between(1, 5, Priority)
        ->  true
        ;   throw(parse_error(priority_out_of_range(Priority)))
        )
    ;   throw(parse_error(not_a_number(PrioStr)))
    ),
    (   RawText \== ""
    ->  re_replace("\\s+"/g, " ", RawText, CleanText)
    ;   throw(parse_error(empty_text))
    ).
```

---

## 4. Демонстрация `sub_atom/5`, сравнение `split_string/4` и `atomic_list_concat/3`, обратный режим `string_concat/3`

### Листинг `demo_low_level`:
```prolog
demo_low_level :-
    writeln('1. Демонстрация sub_atom/5'),
    DocID = 'DOC-014',
    sub_atom(DocID, 0, 4, _, Prefix),
    sub_atom(DocID, 4, 3, 0, CodeAtom),
    format('ID: ~w -> Префикс: ~w, Код: ~w~n~n', [DocID, Prefix, CodeAtom]),

    writeln('2. Сравнение split_string/4 и atomic_list_concat/3 на пустом поле и пробелах'),
    SampleStr = "DOC-027 |  | 3 | Текст",
    split_string(SampleStr, "|", " ", SplitRes),
    atom_string(SampleAtom, SampleStr),
    atomic_list_concat(ConcatRes, '|', SampleAtom),
    format('Исходная строка: "~w"~n', [SampleStr]),
    format('split_string/4: ~q~n', [SplitRes]),
    format('atomic_list_concat/3: ~q~n~n', [ConcatRes]),

    writeln('3. Обратный режим string_concat/3'),
    string_concat("DOC-", RestNumber, "DOC-025"),
    format('string_concat("DOC-", Rest, "DOC-025") -> Rest = ~q~n', [RestNumber]).

```
### Результат вызова `?- demo_low_level.`:
```text
?- demo_low_level.
1. Демонстрация sub_atom/5
ID: DOC-014 -> Префикс: DOC-, Код: 014

2. Сравнение split_string/4 и atomic_list_concat/3 на пустом поле и пробелах
Исходная строка: "DOC-027 |  | 3 | Текст"
split_string/4: ["DOC-027","","3","Текст"]
atomic_list_concat/3: ['DOC-027 ','  ',' 3 ',' Текст']

3. Обратный режим string_concat/3
string_concat("DOC-", Rest, "DOC-025") -> Rest = "025"
true.
```

### Объяснение различий:
1. Тип элементов списка: `split_string/4` возвращает список строк (`string` в двойных кавычках), не засоряя таблицу атомов промежуточными кусками текста. `atomic_list_concat/3` возвращает список атомов (`atom` в одинарных кавычках).
2. Обработка пробелов и пустых полей: у `split_string/4` есть третий аргумент `PadChars` (`" "`), который автоматически обрезает пробелы вокруг разделителя `|`. Благодаря этому поле из одних пробелов между двумя `|` превращается в чистую пустую строку `""`, оставаясь строго на 2-й позиции. У `atomic_list_concat/3` нет параметра обрезки пробелов, поэтому все пробелы сохраняются внутри атомов (`'DOC-027 '`, `'  '`, `' 3 '`).

---

## 5. Все регулярные выражения программы с пояснением каждой части шаблона

1. Проверка формата ID документа (`re_match/2`):
   Шаблон: `"^DOC-\\d{3}$"`
   * `^` и `$` — якоря начала и конца строки;
   * `DOC-` — префикс;
   * `\\d{3}` — ровно 3 цифры.
2. Нормализация пробелов в тексте (`re_replace/4` с флагом `/g`):
   Шаблон: `"\\s+"/g`
   * `\\s+` — одна или более подряд идущих пробельных последовательностей;
   * `/g` — глобальный флаг.
3. Шаблон 1 (`dmy_dot`, формат `05.07.2026` в `re_matchsub/4`):
   `"(?<![0-9/.-])(?<day_I>\\d{2})\\.(?<month_I>\\d{2})\\.(?<year_I>\\d{4})(?![0-9/.-])"`
   * `(?<![0-9/.-])` и `(?![0-9/.-])` — негативные просмотры назад и вперёд (защищают от частичного совпадения внутри длинных чисел или нестандартных дат);
   * `(?<day_I>\\d{2})` — именованная группа `day` из 2 цифр с суффиксом `_I` (автоматическая конвертация в `integer`);
   * `\\.` — экранированная точка-разделитель;
   * `(?<month_I>\\d{2})` и `(?<year_I>\\d{4})` — месяц (2 цифры) и год (4 цифры) как целые числа.
4. Шаблон 2 (`iso_dash`, формат `2026-07-05`):
   `"(?<![0-9/.-])(?<year_I>\\d{4})-(?<month_I>\\d{2})-(?<day_I>\\d{2})(?![0-9/.-])"`
   * Именованные целочисленные группы `year_I` (4 цифры), `month_I` (2 цифры) и `day_I` (2 цифры) через дефис `-`.
5. Шаблон 3 (`mdy_slash`, американский формат `07/05/2026`):
   `"(?<![0-9/.-])(?<month_I>\\d{2})/(?<day_I>\\d{2})/(?<year_I>\\d{4})(?![0-9/.-])"`
   * На первом месте группа месяца `month_I`, затем `/` и день `day_I`, затем `/` и год `year_I`.
6. Шаблон 4 (`ru_abbr`, сокращённый русский формат `5 июл. 2026 г.`):
   `"(?<day_I>\\d{1,2})\\s+(?<m_abbr>янв|фев|мар|апр|мая|июн|июл|авг|сен|окт|ноя|дек)\\.\\s*(?<year_I>\\d{4})\\s*г\\."`
   * `(?<day_I>\\d{1,2})` — день (1 или 2 цифры);
   * `(?<m_abbr>...)\\.` — трёхбуквенное сокращение месяца с обязательной точкой;
   * `(?<year_I>\\d{4})\\s*г\\.` — 4 цифры года и обязательное сокращение `г.`.
7. Шаблон 5 (`ru_full`, полный русский формат `5 июля 2026`):
   `"(?<day_I>\\d{1,2})\\s+(?<m_full>января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(?<year_I>\\d{4})(?!\\s*г\\.)"`
   * Распознаёт день, полное название месяца в родительном падеже и 4-значный год.
8. Шаблон поиска кандидатов для `re_foldl/6`:
   `"\\b(\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}|\\d{1,2}\\s+[а-яА-ЯёЁ]+\\.?\\s+\\d{4}(\\s*г\\.)?)\\b"`
   * Находит в тексте все фрагменты, структурно похожие на численную или текстовую дату, чтобы затем отфильтровать те, которые не подошли ни под один из 5 строгих шаблонов.

---

## 6. Журнал отклонённых строк и подтверждение отсутствия дубликатов при повторной загрузке

### Демонстрация вызова `load_file('input.txt')` и отсутствие дубликатов:
```text
?- load_file('D:/prolog/lab4/input.txt'), print_load_summary.

Принято: 27, отклонено: 7
  строка 29: empty_category
  строка 31: not_a_number("3a")
  строка 32: not_a_number("—")
  строка 33: not_a_number("")
  строка 34: priority_out_of_range(7)
  строка 35: priority_out_of_range(-2)
  строка 36: bad_doc_id("BAD-999")
true.

?- load_file('D:/prolog/lab4/input.txt'), print_load_summary.

Принято: 27, отклонено: 7
  строка 29: empty_category
  строка 31: not_a_number("3a")
  строка 32: not_a_number("—")
  строка 33: not_a_number("")
  строка 34: priority_out_of_range(7)
  строка 35: priority_out_of_range(-2)
  строка 36: bad_doc_id("BAD-999")
true.

```

---

## 7. Код пяти аналитических запросов 
Код
```prolog
% ЗАПРОС 1: Распознавание всех форматов набором шаблонов re_matchsub/4
query1_recognized_formats(FormatCounts, RecognizedRecords) :-
    Formats = [dmy_dot, iso_dash, ru_full, mdy_slash, ru_abbr],
    findall(
        _{format: Fmt, count: Cnt},
        ( member(Fmt, Formats),
          aggregate_all(count,
                        (record(DocID, _, _, Text), extract_date_from_text(Text, Fmt, _, _)),
                        Cnt)
        ),
        FormatCounts
    ),
    findall(
        _{doc_id: DocID, format: Fmt, raw_date: Raw},
        ( record(DocID, _, _, Text),
          extract_date_from_text(Text, Fmt, Raw, _)
        ),
        RecognizedRecords
    ).
```
Результат
```text
?- query1_recognized_formats(FormatCounts, RecognizedRecords).
FormatCounts = [_{count:6, format:dmy_dot}, _{count:4, format:iso_dash}, _{count:5, format:ru_full}, _{count:4, format:mdy_slash}, _{count:4, format:ru_abbr}],
RecognizedRecords = [_{doc_id:'DOC-001', format:dmy_dot, raw_date:"05.07.2026"}, _{doc_id:'DOC-002', format:iso_dash, raw_date:"2026-07-05"}, _{doc_id:'DOC-003', format:ru_full, raw_date:"5 июля 2026"}, _{doc_id:'DOC-004', format:mdy_slash, raw_date:"07/05/2026"}, _{doc_id:'DOC-005', format:ru_abbr, raw_date:"5 июл. 2026 г."}, _{doc_id:'DOC-006', format:dmy_dot, raw_date:"12.01.2026"}, _{doc_id:'DOC-007', format:iso_dash, raw_date:"2026-03-18"}, _{doc_id:'DOC-008', format:ru_full, raw_date:"14 февраля 2026"}, …{… : …, … : …, … : …}|…].
```
Код
```prolog
% ЗАПРОС 2: Приведение к единому виду date(Y, M, D) и к ISO-строке
query2_normalized_dates(NormalizedList, UniqueIsoDates) :-
    findall(
        _{doc_id: DocID, raw: Raw, term: TermStr, iso: IsoStr},
        ( record(DocID, _, _, Text),
          extract_date_from_text(Text, _, Raw, DateTerm),
          valid_calendar_date(DateTerm),
          term_string(DateTerm, TermStr),
          date_to_iso(DateTerm, IsoStr)
        ),
        NormalizedList
    ),
    setof(
        Iso,
        DocID^Cat^Prio^Text^Fmt^Raw^DateTerm^(
            record(DocID, Cat, Prio, Text),
            extract_date_from_text(Text, Fmt, Raw, DateTerm),
            valid_calendar_date(DateTerm),
            date_to_iso(DateTerm, Iso)
        ),
        UniqueIsoDates
    ).
```
Результат
```text
?- query2_normalized_dates(NormalizedList, UniqueIsoDates).
NormalizedList = [_{doc_id:'DOC-001', iso:"2026-07-05", raw:"05.07.2026", term:"date(2026,7,5)"}, _{doc_id:'DOC-002', iso:"2026-07-05", raw:"2026-07-05", term:"date(2026,7,5)"}, _{doc_id:'DOC-003', iso:"2026-07-05", raw:"5 июля 2026", term:"date(2026,7,5)"}, _{doc_id:'DOC-004', iso:"2026-07-05", raw:"07/05/2026", term:"date(2026,7,5)"}, _{doc_id:'DOC-005', iso:"2026-07-05", raw:"5 июл. 2026 г.", term:"date(2026,7,5)"}, _{doc_id:'DOC-006', iso:"2026-01-12", raw:"12.01.2026", term:"date(2026,1,12)"}, _{doc_id:'DOC-007', iso:"2026-03-18", raw:"2026-03-18", term:"date(2026,3,18)"}, _{doc_id:'DOC-008', iso:"2026-02-14", raw:"14 февраля 2026", term:"date(2026,2,14)"}, …{… : …, … : …, … : …, … : …}|…],
UniqueIsoDates = ["2025-12-03", "2026-01-12", "2026-01-15", "2026-02-10", "2026-02-14", "2026-03-18", "2026-04-15", "2026-05-09", "2026-06-30"|…].
```
Код
```prolog
% ЗАПРОС 3: Проверка корректности даты (31 апреля, 29 февраля невисокосного года и т.д.)
query3_date_validation(SummaryDict) :-
    aggregate_all(count,
                  (record(_, _, _, T), extract_date_from_text(T, _, _, Dt), valid_calendar_date(Dt)),
                  ValidCount),
    findall(
        _{doc_id: DocID, raw_date: Raw, parsed_term: TermStr, error: "calendar_invalid_date"},
        ( record(DocID, _, _, T),
          extract_date_from_text(T, _, Raw, Dt),
          \+ valid_calendar_date(Dt),
          term_string(Dt, TermStr)
        ),
        InvalidList
    ),
    length(InvalidList, InvalidCount),
    SummaryDict = _{valid_count: ValidCount, invalid_count: InvalidCount, invalid_dates: InvalidList}.
```
Результат
```text
?- query3_date_validation(SummaryDict).
SummaryDict = _{invalid_count:3, invalid_dates:[_{doc_id:'DOC-021', error:"calendar_invalid_date", parsed_term:"date(2026,4,31)", raw_date:"31 апреля 2026"}, _{doc_id:'DOC-022', error:"calendar_invalid_date", parsed_term:"date(2025,2,29)", raw_date:"29.02.2025"}, _{doc_id:'DOC-023', error:"calendar_invalid_date", parsed_term:"date(2026,11,31)", raw_date:"31.11.2026"}], valid_count:20}.
```
Код
```prolog
% ЗАПРОС 4: Фрагменты, похожие на дату, но не распознанные ни одним шаблоном
collect_candidate_match(Dict, Acc, [MatchStr | Acc]) :-
    get_dict(0, Dict, MatchStr).

find_date_like_candidates(Text, Candidates) :-
    Pattern = "\\b(\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}\\b|\\d{1,2}\\s+[а-яА-ЯёЁ]+\\.?\\s+\\d{4}(?:\\s*г\\.|\\b))",
    re_foldl(collect_candidate_match, Pattern, Text, [], RevCandidates, []),
    reverse(RevCandidates, Candidates).

query4_unrecognized_fragments(UnrecognizedList) :-
    findall(
        _{doc_id: DocID, fragment: Cand, text: Text},
        ( record(DocID, _, _, Text),
          find_date_like_candidates(Text, Candidates),
          member(Cand, Candidates),
          \+ extract_date_from_text(Cand, _, _, _)
        ),
        UnrecognizedList
    ).
```
Результат
```text
?- query4_unrecognized_fragments(UnrecognizedList).
UnrecognizedList = [_{doc_id:'DOC-024', fragment:"2026/08/19", text:"В старом письме партнера указана нестандартная дата 2026/08/19 для поставки первой партии"}, _{doc_id:'DOC-025', fragment:"05-07-26", text:"В платежном поручении обнаружен обрезанный штамп 05-07-26 без указания полного века"}, _{doc_id:'DOC-026', fragment:"15 мартобря 2026", text:"Шуточное мероприятие в расписании клуба стояло на 15 мартобря 2026 после весенней сессии"}].
```
Код
```prolog
% ЗАПРОС 5: Хронологически упорядоченный список событий с контекстом (10 слов вокруг даты)
extract_10_words_context(Text, RawDate, ContextStr) :-
    (   sub_string(Text, BeforeLen, DateLen, AfterLen, RawDate)
    ->  sub_string(Text, 0, BeforeLen, _, LeftPart),
        AfterStart is BeforeLen + DateLen,
        sub_string(Text, AfterStart, AfterLen, 0, RightPart),
        split_string(LeftPart, " ", " ", LeftWords0),
        exclude(=(""), LeftWords0, LeftWords),
        split_string(RightPart, " ", " ", RightWords0),
        exclude(=(""), RightWords0, RightWords),
        take_last_n(5, LeftWords, ContextLeft),
        take_first_n(5, RightWords, ContextRight),
        append(ContextLeft, [RawDate | ContextRight], AllWords),
        atomic_list_concat(AllWords, ' ', ContextAtom),
        atom_string(ContextAtom, ContextStr)
    ;   ContextStr = Text
    ).

take_first_n(N, List, Taken) :-
    length(List, Len),
    ( Len =< N -> Taken = List ; length(Taken, N), append(Taken, _, List) ).

take_last_n(N, List, Taken) :-
    reverse(List, Rev),
    take_first_n(N, Rev, TakenRev),
    reverse(TakenRev, Taken).

query5_chronological_events(SortedEvents) :-
    findall(
        SortKey - _{
            iso_date: IsoStr,
            doc_id: DocID,
            category: Category,
            priority: Priority,
            raw_date: RawDate,
            context_10_words: Context
        },
        ( record(DocID, Category, Priority, Text),
          extract_date_from_text(Text, _, RawDate, date(Y, M, D)),
          valid_calendar_date(date(Y, M, D)),
          SortKey is Y * 10000 + M * 100 + D,
          date_to_iso(date(Y, M, D), IsoStr),
          extract_10_words_context(Text, RawDate, Context)
        ),
        KeyedEvents
    ),
    msort(KeyedEvents, SortedKeyed),
    pairs_values(SortedKeyed, SortedEvents).

pairs_values([], []).
pairs_values([_-V | T], [V | VT]) :- pairs_values(T, VT).
```
Результат
```text
?- query5_chronological_events(SortedEvents).
SortedEvents = [_{category:report, context_10_words:"выписка по старым проектам сформирована 3 дек. 2025 г. сотрудником архивной службы", doc_id:'DOC-015', iso_date:"2025-12-03", priority:1, raw_date:"3 дек. 2025 г."}, _{category:contract, context_10_words:"Подписание международного контракта перенесено на 12.01.2026 из-за согласования юридических правок", doc_id:'DOC-006', iso_date:"2026-01-12", priority:5, raw_date:"12.01.2026"}, _{category:release, context_10_words:"Патч критической уязвимости безопасности выпущен 15 янв. 2026 г. экстренной группой реагирования", doc_id:'DOC-020', iso_date:"2026-01-15", priority:5, raw_date:"15 янв. 2026 г."}, _{category:maintenance, context_10_words:"маршрутизаторов в дата-центре запланирована на 02/10/2026 дежурной сменой", doc_id:'DOC-019', iso_date:"2026-02-10", priority:3, raw_date:"02/10/2026"}, _{category:training, context_10_words:"Практический семинар для стажеров пройдет 14 февраля 2026 в учебном центре компании", doc_id:'DOC-008', iso_date:"2026-02-14", priority:2, raw_date:"14 февраля 2026"}, _{category:conference, context_10_words:"конференция по искусственному интеллекту откроется 2026-03-18 в технопарке Сколково", doc_id:'DOC-007', iso_date:"2026-03-18", priority:3, raw_date:"2026-03-18"}, _{category:finance, context_10_words:"годовых бонусов сотрудникам департамента ожидается 04/15/2026 после закрытия реестра", doc_id:'DOC-009', iso_date:"2026-04-15", priority:4, raw_date:"04/15/2026"}, _{category:audit, context_10_words:"Внутренняя ревизия складских остатков проведена 9 мая 2026 комиссией главного управления", doc_id:'DOC-013', iso_date:"2026-05-09", priority:3, raw_date:"9 мая 2026"}, …{… : …, … : …, … : …, … : …, … : …, … : …}|…].
```
---

## 8. Получение JSON и CSV файлов и объяснение изменения типов при обратном чтении
```prolog
### Фрагмент `clean.csv`:
export_and_verify(JsonFile, CsvFile) :-
    query1_recognized_formats(FmtCounts, RecRecords),
    query2_normalized_dates(NormList, UniqueIso),
    query3_date_validation(ValSummary),
    query4_unrecognized_fragments(UnrecList),
    query5_chronological_events(Events),
    JsonDict = _{
        variant: 14,
        title: "Нормализация дат",
        query1_formats: _{summary: FmtCounts, recognized: RecRecords},
        query2_normalized: _{items: NormList, unique_iso_dates: UniqueIso},
        query3_validation: ValSummary,
        query4_unrecognized: UnrecList,
        query5_chronology: Events
    },
    setup_call_cleanup(
        open(JsonFile, write, JsonOut, [encoding(utf8)]),
        json_write_dict(JsonOut, JsonDict, [width(120)]),
        close(JsonOut)
    ),

    findall(row(DocID, Category, Priority, Text),
            record(DocID, Category, Priority, Text),
            DataRows),
    HeaderRow = row('doc_id', 'category', 'priority', 'text'),
    csv_write_file(CsvFile, [HeaderRow | DataRows], [encoding(utf8)]),

    setup_call_cleanup(
        open(JsonFile, read, JsonIn, [encoding(utf8)]),
        json_read_dict(JsonIn, ReadJsonDict),
        close(JsonIn)
    ),
    csv_read_file(CsvFile, [ _ReadHeader | ReadCsvRows ], [encoding(utf8)]),
    length(DataRows, OrigCsvCount),
    length(ReadCsvRows, ReadCsvCount),
    length(Events, OrigEventsCount),
    length(ReadJsonDict.query5_chronology, ReadEventsCount),
    format('CSV: записано ~d строк данных, прочитано обратно ~d строк.~n', [OrigCsvCount, ReadCsvCount]),
    format('JSON: записано ~d хронологических событий, прочитано обратно ~d событий.~n', [OrigEventsCount, ReadEventsCount]).
```

```json
"query1_formats": {
    "recognized": [
      {"doc_id":"DOC-001","format":"dmy_dot","raw_date":"05.07.2026"},
      {"doc_id":"DOC-002","format":"iso_dash","raw_date":"2026-07-05"},
      {"doc_id":"DOC-003","format":"ru_full","raw_date":"5 июля 2026"},
      {"doc_id":"DOC-004","format":"mdy_slash","raw_date":"07/05/2026"},
      {"doc_id":"DOC-005","format":"ru_abbr","raw_date":"5 июл. 2026 г."},
...
    "summary": [
      {"count":6,"format":"dmy_dot"},
      {"count":4,"format":"iso_dash"},
      {"count":5,"format":"ru_full"},
      {"count":4,"format":"mdy_slash"},
      {"count":4,"format":"ru_abbr"}
    ]
...
```
```cvs
doc_id,category,priority,text
DOC-001,meeting,3,Встреча отдела разработки назначена на 05.07.2026 в главном офисе компании для обсуждения архитектуры
DOC-002,release,5,Релиз основной ветки платформы запланирован на 2026-07-05 после завершения нагрузочного тестирования серверов
DOC-003,meeting,2,Ежегодное собрание акционеров состоится 5 июля 2026 в большом конференц-зале башни Федерация
DOC-004,audit,4,Внешний аудит безопасности инфраструктуры начнется 07/05/2026 с проверки правил межсетевого экрана
DOC-005,report,1,Финальный квартальный отчет необходимо сдать до 5 июл. 2026 г. включительно без задержек
DOC-006,contract,5,Подписание международного контракта перенесено на 12.01.2026 из-за согласования юридических правок
```
### Объяснение изменения типов при обратном чтении (`csv_read_file/3` и `json_read_dict/2`):
1. При чтении CSV (`csv_read_file/3`): все текстовые колонки (включая поле `Text`, которое в базе знаний было типа `string`) по умолчанию десериализуются в атомы (`atom`), а числовые поля (`3`, `5`) автоматически преобразуются в числа (`integer`).
2. При чтении JSON (`json_read_dict/2`): в формате JSON не существует различия между атомами и строками. Поэтому при обратном чтении поля `doc_id` и `category`, которые в Prolog были атомами (`'DOC-001'`, `'meeting'`), возвращаются как строки (`"DOC-001"`, `"meeting"`), тогда как ключи словарей преобразуются в атомы, а числовые значения (`priority`, `count`) сохраняют тип `integer`.

---

## 9. Полный вывод `print_report/0` и единой точки входа `run/3`

```text
?- run('d:/prolog/lab4/input.txt', 'd:/prolog/lab4/result.json', 'd:/prolog/lab4/clean.csv').

Принято: 27, отклонено: 7
  строка 29: empty_category
  строка 31: not_a_number("3a")
  строка 32: not_a_number("—")
  строка 33: not_a_number("")
  строка 34: priority_out_of_range(7)
  строка 35: priority_out_of_range(-2)
  строка 36: bad_doc_id("BAD-999")

[1] Статистика распознанных форматов дат:
    Формат dmy_dot: 6 записей
    Формат iso_dash: 4 записей
    Формат ru_full: 5 записей
    Формат mdy_slash: 4 записей
    Формат ru_abbr: 4 записей

[2] Приведено к единому виду date(Y,M,D) и ISO: 20 валидных дат (уникальных: 16)

[3] Проверка календарной корректности (отклонено 3 некорректных дат):
    [DOC-021] Найдено: "31 апреля 2026" -> date(2026,4,31) (Ошибка: не существует в календаре)
    [DOC-022] Найдено: "29.02.2025" -> date(2025,2,29) (Ошибка: не существует в календаре)
    [DOC-023] Найдено: "31.11.2026" -> date(2026,11,31) (Ошибка: не существует в календаре)

[4] Фрагменты, похожие на дату, но не распознанные ни одним шаблоном:
    [DOC-024] Нераспознанный фрагмент: "2026/08/19"
    [DOC-025] Нераспознанный фрагмент: "05-07-26"
    [DOC-026] Нераспознанный фрагмент: "15 мартобря 2026"

[5] Хронологический список событий:
ISO Дата      ID        Категория   Контекст
--------------------------------------------------------------------------------
2025-12-03    DOC-015   report      выписка по старым проектам сформирована 3 дек. 2025 г. сотрудником архивной службы
2026-01-12    DOC-006   contract    Подписание международного контракта перенесено на 12.01.2026 из-за согласования юридических правок
2026-01-15    DOC-020   release     Патч критической уязвимости безопасности выпущен 15 янв. 2026 г. экстренной группой реагирования
2026-02-10    DOC-019   maintenance маршрутизаторов в дата-центре запланирована на 02/10/2026 дежурной сменой
2026-02-14    DOC-008   training    Практический семинар для стажеров пройдет 14 февраля 2026 в учебном центре компании
2026-03-18    DOC-007   conference  конференция по искусственному интеллекту откроется 2026-03-18 в технопарке Сколково
2026-04-15    DOC-009   finance     годовых бонусов сотрудникам департамента ожидается 04/15/2026 после закрытия реестра
2026-05-09    DOC-013   audit       Внутренняя ревизия складских остатков проведена 9 мая 2026 комиссией главного управления
2026-06-30    DOC-017   training    Курс повышения квалификации инженеров завершится 2026-06-30 вручением номерных сертификатов
2026-07-05    DOC-005   report      квартальный отчет необходимо сдать до 5 июл. 2026 г. включительно без задержек
2026-07-05    DOC-004   audit       Внешний аудит безопасности инфраструктуры начнется 07/05/2026 с проверки правил межсетевого экрана
2026-07-05    DOC-003   meeting     Ежегодное собрание акционеров состоится 5 июля 2026 в большом конференц-зале башни Федерация
2026-07-05    DOC-002   release     основной ветки платформы запланирован на 2026-07-05 после завершения нагрузочного тестирования серверов
2026-07-05    DOC-001   meeting     Встреча отдела разработки назначена на 05.07.2026 в главном офисе компании для
2026-08-22    DOC-010   maintenance Плановое обновление базы данных стартует 22 авг. 2026 г. в ночное окно обслуживания
2026-09-01    DOC-011   release     приложения в продакшн намечен на 01.09.2026 для всех регионов присутствия
2026-09-19    DOC-018   conference  разработчиков систем хранения данных пройдет 19 сентября 2026 в Санкт-Петербурге
2026-10-10    DOC-012   contract    лицензий на серверное оборудование истекает 2026-10-10 в полночь по московскому времени
2026-11-20    DOC-014   meeting     Стратегическая сессия совета директоров пройдет 11/20/2026 в закрытом формате видеоконференции
2026-12-25    DOC-016   finance     года и сдача баланса состоятся 25.12.2026 при участии главных бухгалтеров
CSV: записано 27 строк данных, прочитано обратно 27 строк.
JSON: записано 20 хронологических событий, прочитано обратно 20 событий.
true.
```

---

## 10. Пример строки, которую ручной `split_string/4` разбирает неверно, и результат `csv_read_file/3`

Если в CSV-строке внутри текстового поля, заключённого в кавычки, встречается запятая, вызов `split_string/4` разрезает строку прямо внутри цитаты:

```text
?- demo_csv_vs_split.
Исходная CSV-строка: "DOC-001","meeting",3,"Встреча, обсуждение бюджета, фаза 2"
Ручной split_string/4: ["DOC-001","meeting","3","Встреча","обсуждение бюджета","фаза 2"]
Стандартный csv_read: row('DOC-001',meeting,3,'Встреча, обсуждение бюджета, фаза 2')
true.
```

---

## Выводы

В ходе выполнения лабораторной работы был разработан полный конвейер обработки текстовых данных на языке Prolog (Вариант 14 — «Нормализация дат»):
1. Обосновано разделение типов между `atom` (идентификаторы `DocID`, категории `Category`) и `string` (свободный текст описания событий), что предотвращает переполнение таблицы атомов при чтении больших файлов.
2. Реализован отказоустойчивый парсер `parse_line/2` с использованием `split_string/4` и механизма исключений `catch/3` — `throw/1`, обеспечивающий изоляцию «грязных» записей в журнал `rejected/3` без аварийной остановки программы.
3. С помощью библиотеки `library(pcre)` (`re_match/2`, `re_matchsub/4` с типизированными группами `_I`, `re_foldl/6`, `re_replace/4`) выполнено извлечение дат в 5 различных форматах, их приведение к каноническому терму `date(Y, M, D)` и формату ISO-8601, проверка календарной корректности (включая правило високосного года) и построение хронологической ленты событий с контекстом.
