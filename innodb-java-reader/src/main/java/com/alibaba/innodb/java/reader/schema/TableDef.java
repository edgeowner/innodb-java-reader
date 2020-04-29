/*
 * Copyright (C) 1999-2019 Alibaba Group Holding Limited
 */
package com.alibaba.innodb.java.reader.schema;

import com.google.common.collect.ImmutableList;

import com.alibaba.innodb.java.reader.CharsetMapping;
import com.alibaba.innodb.java.reader.exception.SqlParseException;
import com.alibaba.innodb.java.reader.util.Symbol;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import static com.alibaba.innodb.java.reader.Constants.DEFAULT_JAVA_CHARSET;
import static com.alibaba.innodb.java.reader.Constants.DEFAULT_MYSQL_CHARSET;
import static com.alibaba.innodb.java.reader.column.ColumnType.CHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Table definition, like the result of the sql command: <code>SHOW CREATE TABLE LIKE 'TTT'</code>.
 *
 * @author xu.zx
 */
@EqualsAndHashCode
@Slf4j
public class TableDef {

  private String name;

  private List<String> columnNames;

  private List<Column> columnList;

  private Map<String, Field> nameToFieldMap;

  private List<Column> primaryKeyColumns;

  private List<String> primaryKeyColumnNames;

  private List<Column> primaryKeyVarLenColumns;

  private List<String> primaryKeyVarLenColumnNames;

  private int nullableColumnNum = 0;

  private int variableLengthColumnNum = 0;

  private List<Column> nullableColumnList;

  private List<Column> variableLengthColumnList;

  /**
   * Column ordinal.
   */
  private int ordinal = 0;

  /**
   * Default charset for decoding string in Java. Derived from table DDL default charset
   * according to {@link CharsetMapping}.
   */
  private String defaultJavaCharset = DEFAULT_JAVA_CHARSET;

  /**
   * Table DDL default charset, for example can be latin, utf8, utf8mb4.
   */
  private String defaultCharset = DEFAULT_MYSQL_CHARSET;

  /**
   * //TODO make sure this is the right way to implement
   * For example, if table charset set to utf8, then it will consume up to 3 bytes for one character.
   * if it is utf8mb4, then it must be set to 4.
   */
  private int maxBytesPerChar = 1;

  public TableDef() {
    this.columnList = new ArrayList<>();
    this.columnNames = new ArrayList<>();
    this.nameToFieldMap = new HashMap<>();
    this.nullableColumnList = new ArrayList<>();
    this.variableLengthColumnList = new ArrayList<>();
  }

  public void validate() {
    checkState(CollectionUtils.isNotEmpty(columnList), "No column is specified");
    if (CollectionUtils.isEmpty(primaryKeyColumns)) {
      log.debug("No primary key is specified {}", name);
    }
  }

  public boolean containsVariableLengthColumn() {
    return variableLengthColumnNum > 0;
  }

  public boolean containsNullColumn() {
    return nullableColumnNum > 0;
  }

  public List<Column> getColumnList() {
    return columnList;
  }

  public List<String> getColumnNames() {
    return columnNames;
  }

  public int getColumnNum() {
    return columnNames.size();
  }

  public int getNullableColumnNum() {
    return nullableColumnNum;
  }

  public int getVariableLengthColumnNum() {
    return variableLengthColumnNum;
  }

  public TableDef addColumn(Column column) {
    checkNotNull(column, "Column should not be null");
    checkArgument(StringUtils.isNotEmpty(column.getName()), "Column name is empty");
    checkArgument(StringUtils.isNotEmpty(column.getType()), "Column type is empty");
    checkArgument(!nameToFieldMap.containsKey(column.getName()), "Duplicate column name");
    if (column.isPrimaryKey()) {
      checkState(CollectionUtils.isEmpty(primaryKeyColumns), "Primary key is already defined");
      checkState(CollectionUtils.isEmpty(primaryKeyColumnNames), "Primary key names is already defined");
      primaryKeyColumns = ImmutableList.of(column);
      primaryKeyColumnNames = ImmutableList.of(column.getName());
      if (isVarLen(column)) {
        primaryKeyVarLenColumns = ImmutableList.of(column);
        primaryKeyVarLenColumnNames = ImmutableList.of(column.getName());
      } else {
        primaryKeyVarLenColumns = ImmutableList.of();
        primaryKeyVarLenColumnNames = ImmutableList.of();
      }
    }
    if (column.isNullable()) {
      nullableColumnList.add(column);
      nullableColumnNum++;
    }
    if (column.isVariableLength()) {
      variableLengthColumnList.add(column);
      variableLengthColumnNum++;
    } else if (CHAR.equals(column.getType()) && maxBytesPerChar > 1) {
      // 多字符集则设置为varchar的读取方式
      column.setVarLenChar(true);
      variableLengthColumnList.add(column);
      variableLengthColumnNum++;
    }
    column.setOrdinal(ordinal++);
    column.setTableDef(this);
    columnList.add(column);
    columnNames.add(column.getName());
    nameToFieldMap.put(column.getName(), new Field(column.getOrdinal(), column.getName(), column));
    return this;
  }

  public Field getField(String columnName) {
    return nameToFieldMap.get(columnName);
  }

  public List<Column> getPrimaryKeyColumns() {
    return primaryKeyColumns;
  }

  public List<String> getPrimaryKeyColumnNames() {
    return primaryKeyColumnNames;
  }

  public int getPrimaryKeyColumnNum() {
    return primaryKeyColumnNames == null ? 0 : primaryKeyColumnNames.size();
  }

  public List<Column> getPrimaryKeyVarLenColumns() {
    return primaryKeyVarLenColumns;
  }

  public List<String> getPrimaryKeyVarLenColumnNames() {
    return primaryKeyVarLenColumnNames;
  }

  public boolean isColumnPrimaryKey(Column column) {
    return primaryKeyColumnNames != null && primaryKeyColumnNames.contains(column.getName());
  }

  public TableDef setPrimaryKeyColumns(List<String> primaryKeyColumnNames) {
    checkState(CollectionUtils.isEmpty(this.primaryKeyColumns), "Primary key is already defined in column");

    ImmutableList.Builder<Column> pkCols = ImmutableList.builder();
    ImmutableList.Builder<String> pkColNames = ImmutableList.builder();
    ImmutableList.Builder<Column> varLenPkCols = ImmutableList.builder();
    ImmutableList.Builder<String> varLenPkColNames = ImmutableList.builder();
    for (String colName : primaryKeyColumnNames) {
      String pkColumnName = colName.replace(Symbol.BACKTICK, Symbol.EMPTY)
          .replace(Symbol.DOUBLE_QUOTE, Symbol.EMPTY);
      if (containsColumn(pkColumnName)) {
        Column pk = getField(pkColumnName).getColumn();
        pkCols.add(pk);
        pkColNames.add(pkColumnName);

        if (isVarLen(pk)) {
          varLenPkCols.add(pk);
          varLenPkColNames.add(pkColumnName);
        }
      } else {
        throw new SqlParseException("Column " + pkColumnName + " is not defined, so it cannot be primary key ");
      }
    }

    this.primaryKeyColumns = pkCols.build();
    this.primaryKeyColumnNames = pkColNames.build();
    this.primaryKeyVarLenColumns = varLenPkCols.build();
    this.primaryKeyVarLenColumnNames = varLenPkColNames.build();
    return this;
  }

  private boolean isVarLen(Column pk) {
    return pk.isVariableLength()
        || (CHAR.equals(pk.getType()) && maxBytesPerChar > 1);
  }

  public List<Column> getVariableLengthColumnList() {
    return variableLengthColumnList;
  }

  public List<Column> getNullableColumnList() {
    return nullableColumnList;
  }

  public String getDefaultJavaCharset() {
    return defaultJavaCharset;
  }

  public String getDefaultCharset() {
    return defaultCharset;
  }

  public TableDef setDefaultCharset(String defaultCharset) {
    checkArgument(CollectionUtils.isEmpty(columnList), "Default charset should be set before adding columns");
    this.defaultCharset = defaultCharset;
    this.defaultJavaCharset = CharsetMapping.getJavaCharsetForMysqlCharset(defaultCharset);
    this.maxBytesPerChar = CharsetMapping.getMaxByteLengthForMysqlCharset(defaultCharset);
    return this;
  }

  public String getName() {
    return name;
  }

  public TableDef setName(String name) {
    this.name = name
        .replace(Symbol.BACKTICK, Symbol.EMPTY)
        .replace(Symbol.DOUBLE_QUOTE, Symbol.EMPTY);
    return this;
  }

  public int getMaxBytesPerChar() {
    return maxBytesPerChar;
  }

  public boolean containsColumn(String columnName) {
    return nameToFieldMap.containsKey(columnName);
  }

  @Data
  public class Field {
    private int ordinal;
    private String name;
    private Column column;

    public Field(int ordinal, String name, Column column) {
      this.ordinal = ordinal;
      this.name = name;
      this.column = column;
    }
  }

  @Override
  public String toString() {
    return toString(false);
  }

  public String toString(boolean multiLine) {
    StringBuilder sb = new StringBuilder();
    sb.append("CREATE TABLE ");
    sb.append(name == null ? "<undefined>" : name);
    sb.append(" (");
    for (int i = 0; i < columnList.size(); i++) {
      Column column = columnList.get(i);
      if (multiLine) {
        sb.append("\n");
      }
      sb.append(column.getName());
      sb.append(" ");
      sb.append(column.getFullType());
      if (!column.isNullable()) {
        sb.append(" NOT NULL");
      }
      if (column.isPrimaryKey()) {
        sb.append(" PRIMARY KEY");
      }
      if (i != columnList.size() - 1) {
        sb.append(",");
      }
    }
    if (CollectionUtils.isNotEmpty(primaryKeyColumns)) {
      if (multiLine) {
        sb.append("\n");
      }
      sb.append(",");
      sb.append("PRIMARY KEY")
          .append("(")
          .append(primaryKeyColumns.stream()
              .map(Column::getName).collect(Collectors.joining(",")))
          .append(")");
    }

    sb.append(")");
    if (multiLine) {
      sb.append("\n");
    }
    sb.append("ENGINE = InnoDB");
    if (StringUtils.isNotEmpty(defaultCharset)) {
      sb.append(" DEFAULT CHARSET = ").append(defaultCharset);
    }
    sb.append(";");
    return sb.toString();
  }
}
