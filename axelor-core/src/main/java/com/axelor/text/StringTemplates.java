/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2020 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.text;

import com.axelor.common.StringUtils;
import com.axelor.db.EntityHelper;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.meta.MetaStore;
import com.axelor.script.ScriptBindings;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import com.google.common.xml.XmlEscapers;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.stringtemplate.v4.AttributeRenderer;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.DateRenderer;
import org.stringtemplate.v4.Interpreter;
import org.stringtemplate.v4.NumberRenderer;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import org.stringtemplate.v4.StringRenderer;
import org.stringtemplate.v4.compiler.Bytecode;
import org.stringtemplate.v4.misc.ObjectModelAdaptor;
import org.stringtemplate.v4.misc.STNoSuchPropertyException;

/** The implementation of {@link Templates} for the StringTemplate (ST4) support. */
public class StringTemplates implements Templates {

  class StrRenderer extends StringRenderer {

    @Override
    public String toString(Object o, String formatString, Locale locale) {
      final String str = (String) o;
      if (StringUtils.notBlank(formatString)) {
        if (formatString.endsWith("escape")) {
          return XmlEscapers.xmlAttributeEscaper().escape(str);
        }
        if (formatString.startsWith("selection:")) {
          return getSelectionTitle(formatString.substring(10).trim(), o.toString());
        }
      }
      return super.toString(str, formatString, locale);
    }
  }

  class LocalDateRenderer implements AttributeRenderer {

    @Override
    public String toString(Object o, String formatString, Locale locale) {
      return StringUtils.isBlank(formatString)
          ? o.toString()
          : ((LocalDate) o).format(DateTimeFormatter.ofPattern(formatString));
    }
  }

  class LocalDateTimeRenderer implements AttributeRenderer {

    @Override
    public String toString(Object o, String formatString, Locale locale) {
      return StringUtils.isBlank(formatString)
          ? o.toString()
          : ((LocalDateTime) o).format(DateTimeFormatter.ofPattern(formatString));
    }
  }

  class LocalTimeRenderer implements AttributeRenderer {

    @Override
    public String toString(Object o, String formatString, Locale locale) {
      return StringUtils.isBlank(formatString)
          ? o.toString()
          : ((LocalTime) o).format(DateTimeFormatter.ofPattern(formatString));
    }
  }

  class DataAdapter extends ObjectModelAdaptor {

    @Override
    public Object getProperty(
        Interpreter interp, ST self, Object o, Object property, String propertyName)
        throws STNoSuchPropertyException {

      if (!(o instanceof Model)) {
        return super.getProperty(interp, self, o, property, propertyName);
      }

      final Mapper mapper = Mapper.of(EntityHelper.getEntityClass(o));
      final Property field = mapper.getProperty(propertyName);

      if (field == null) {
        return null;
      }

      if (StringUtils.isBlank(field.getSelection())) {
        return field.get(o);
      }
      return getSelectionTitle(field.getSelection(), field.get(o));
    }
  }

  class StringTemplate implements Template {

    private ST template;
    private Set<String> names;

    private StringTemplate(ST template) {
      this.template = template;
      this.names = findAttributes();
    }

    private Set<String> findAttributes() {
      Set<String> names = Sets.newHashSet();
      int ip = 0;
      while (ip < template.impl.codeSize) {
        int opcode = template.impl.instrs[ip];
        Bytecode.Instruction I = Bytecode.instructions[opcode];
        ip++;
        for (int opnd = 0; opnd < I.nopnds; opnd++) {
          if (opcode == Bytecode.INSTR_LOAD_ATTR) {
            int nameIndex = Interpreter.getShort(template.impl.instrs, ip);
            if (nameIndex < template.impl.strings.length) {
              names.add(template.impl.strings[nameIndex]);
            }
          }
          ip += Bytecode.OPND_SIZE_IN_BYTES;
        }
      }
      return names;
    }

    @Override
    public Renderer make(Map<String, Object> context) {
      return new Renderer() {
        @Override
        public void render(Writer out) throws IOException {
          final ScriptBindings vars = new ScriptBindings(context);
          for (String name : names) {
            try {
              template.add(name, vars.get(name));
            } catch (Exception e) {
            }
          }
          try {
            template.write(new AutoIndentWriter(out));
          } catch (IOException e) {
          }
        }
      };
    }

    @Override
    public <T extends Model> Renderer make(T context) {
      final Map<String, Object> ctx = new HashMap<>();
      if (context != null) {
        Mapper mapper = Mapper.of(EntityHelper.getEntityClass(context));
        for (String name : names) {
          Property property = mapper.getProperty(name);
          if (property != null) {
            ctx.put(name, property.get(context));
          }
        }
      }
      return make(ctx);
    }
  }

  private static final char DEFAULT_START_DELIMITER = '<';
  private static final char DEFAULT_STOP_DELIMITER = '>';

  private final STGroup group;

  public StringTemplates() {
    this(DEFAULT_START_DELIMITER, DEFAULT_STOP_DELIMITER);
  }

  public StringTemplates(char delimiterStartChar, char delimiterStopChar) {
    this.group = new STGroup(delimiterStartChar, delimiterStopChar);

    // Custom renderers
    this.group.registerRenderer(String.class, new StrRenderer());
    this.group.registerRenderer(LocalDate.class, new LocalDateRenderer());
    this.group.registerRenderer(LocalDateTime.class, new LocalDateTimeRenderer());
    this.group.registerRenderer(LocalTime.class, new LocalTimeRenderer());
    this.group.registerModelAdaptor(Object.class, new DataAdapter());

    // Other renderers provide by ST
    this.group.registerRenderer(Number.class, new NumberRenderer());
    this.group.registerRenderer(Date.class, new DateRenderer());
  }

  @Override
  public Template fromText(String text) {
    ST template = new ST(group, text);
    return new StringTemplate(template);
  }

  @Override
  public Template from(File file) throws IOException {
    return from(new FileReader(file));
  }

  @Override
  public Template from(Reader reader) throws IOException {
    return fromText(CharStreams.toString(reader));
  }

  private String valueOf(Object value) {
    if (value == null) return "";
    return String.valueOf(value);
  }

  private String getSelectionTitle(String name, Object value) {
    final String val = valueOf(value);
    if (StringUtils.isBlank(val)) return val;
    try {
      return MetaStore.getSelectionItem(name, val).getLocalizedTitle();
    } catch (Exception e) {
    }
    return val;
  }
}
