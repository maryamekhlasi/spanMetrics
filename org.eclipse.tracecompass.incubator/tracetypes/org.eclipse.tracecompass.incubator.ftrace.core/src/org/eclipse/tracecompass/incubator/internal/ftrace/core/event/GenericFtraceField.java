/*******************************************************************************
 * Copyright (c) 2018 Ecole Polytechnique de Montreal
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0 which
 * accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.tracecompass.incubator.internal.ftrace.core.event;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tracecompass.analysis.os.linux.core.kernel.LinuxValues;
import org.eclipse.tracecompass.incubator.internal.ftrace.core.layout.GenericFtraceEventLayout;
import org.eclipse.tracecompass.tmf.core.event.ITmfEventField;
import org.eclipse.tracecompass.tmf.core.event.TmfEventField;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ftrace field class
 *
 * @author Guillaume Champagne
 * @author Alexis-Maurer Fortin
 * @author Hugo Genesse
 * @author Pierre-Yves Lajoie
 * @author Eva Terriault
 */
@NonNullByDefault
public class GenericFtraceField {

    private static final Pattern KEYVAL_KEY_PATTERN = Pattern.compile("(?<key>[^\\s=\\[\\],]+)(?<separator>[=:])"); //$NON-NLS-1$
    private static final Pattern KEYVAL_VALUE_PATTERN = Pattern.compile("\\s*(?<value>[^\\[\\],]+).*"); //$NON-NLS-1$
    private static final Pattern KEYVAL_VALUE_DOCKER_BYPASS = Pattern.compile("\\S+:\\[\\S+:\\S+\\]"); //$NON-NLS-1$
    private static final Map<String, Pattern> KEYVAL_KEY_PATTERN_MAP = Map.of("=", Pattern.compile("(?<key>[^\\s=\\[\\],]+)(?<separator>=)"), //$NON-NLS-1$ //$NON-NLS-2$
            ":", Pattern.compile("(?<key>[^\\s=\\[\\],]+)(?<separator>:)"), //$NON-NLS-1$ //$NON-NLS-2$
            "", KEYVAL_KEY_PATTERN); //$NON-NLS-1$
    private static final String KEYVAL_KEY_GROUP = "key"; //$NON-NLS-1$
    private static final String KEYVAL_VALUE_GROUP = "value"; //$NON-NLS-1$


    private static final double SECONDS_TO_NANO = 1000000000.0;
    private static final Map<Character, @NonNull Long> PREV_STATE_LUT;

    static {
        ImmutableMap.Builder<Character, @NonNull Long> builder = new ImmutableMap.Builder<>();

        builder.put('R', (long) LinuxValues.TASK_STATE_RUNNING);
        builder.put('S', (long) LinuxValues.TASK_INTERRUPTIBLE);
        builder.put('D', (long) LinuxValues.TASK_UNINTERRUPTIBLE);
        builder.put('T', (long) LinuxValues.TASK_STOPPED__);
        builder.put('t', (long) LinuxValues.TASK_TRACED__);
        builder.put('X', (long) LinuxValues.EXIT_ZOMBIE);
        builder.put('x', (long) LinuxValues.EXIT_ZOMBIE);
        builder.put('Z', (long) LinuxValues.EXIT_DEAD);
        builder.put('P', (long) LinuxValues.TASK_DEAD);
        builder.put('I', (long) LinuxValues.TASK_WAKEKILL);
        PREV_STATE_LUT = builder.build();
    }

    private final Long fTs;
    private String fName;
    private final Integer fCpu;
    private @Nullable Integer fTid;
    private @Nullable Integer fPid;
    private ITmfEventField fContent;

    /**
     * Constructor
     *
     * @param name   event name
     * @param cpu    the cpu number
     * @param ts     the timestamp in ns
     * @param pid    the process id
     * @param tid    the threadId
     * @param fields event fields (arguments)
     */
    public GenericFtraceField(String name, Integer cpu, Long ts, @Nullable Integer pid, @Nullable Integer tid, Map<String, Object> fields) {
        fName = name;
        fCpu = cpu;
        fPid = pid;
        fTid = tid;
        ITmfEventField[] array = fields.entrySet().stream()
                .map(entry -> new TmfEventField(entry.getKey(), entry.getValue(), null))
                .toArray(ITmfEventField[]::new);
        fContent = new TmfEventField(ITmfEventField.ROOT_FIELD_ID, fields, array);
        fTs = ts;
    }

    /**
     * Parse a line from an ftrace ouput file
     *
     * @param line The string to parse
     * @return An event field
     */
    public static @Nullable GenericFtraceField parseLine(String line) {
        Matcher matcher = IGenericFtraceConstants.FTRACE_PATTERN.matcher(line);
        if (matcher.matches()) {
            Integer pid = Integer.parseInt(matcher.group(IGenericFtraceConstants.FTRACE_PID_GROUP));
            Integer tid = pid;
            Integer cpu = Integer.parseInt(matcher.group(IGenericFtraceConstants.FTRACE_CPU_GROUP));
            Double timestampInSec = Double.parseDouble(matcher.group(IGenericFtraceConstants.FTRACE_TIMESTAMP_GROUP));
            Long timestampInNano = (long) (timestampInSec * SECONDS_TO_NANO);

            String name = matcher.group(IGenericFtraceConstants.FTRACE_NAME_GROUP);
            name = name.trim();

            String separator = matcher.group(IGenericFtraceConstants.FTRACE_SEPARATOR_GROUP);
            separator = separator.trim();

            String attributes = matcher.group(IGenericFtraceConstants.FTRACE_DATA_GROUP);

            name = eventNameRewrite(name, separator);

            /*
             * There's no distinction between pid and tid in scheduling events. However,when there's a mismatch
             * between the tgid and the pid, we know the event happened on a thread and that
             * the tgid is the actual pid, and the pid the tid.
             */
            String tgid = matcher.group(IGenericFtraceConstants.FTRACE_TGID_GROUP);
            if (tgid != null) {
                Integer tgidNumeric = Integer.parseInt(tgid);
                if (!tgidNumeric.equals(pid)) {
                    pid = tgidNumeric;
                }
            }

            Map<@NonNull String, @NonNull Object> fields = new HashMap<>();

            if (attributes != null && !attributes.isEmpty()) {
                int valStart = 0;
                Matcher keyvalMatcher = KEYVAL_KEY_PATTERN.matcher(attributes);
                String key = null;
                separator = null;
                while (keyvalMatcher.find()) {
                    if (key != null) {
                        int start = keyvalMatcher.start();
                        String value = attributes.substring(0, start);
                        putKeyValueField(name, fields, key, value);
                    }
                    valStart = keyvalMatcher.end();
                    key = keyvalMatcher.group(KEYVAL_KEY_GROUP);
                    separator = keyvalMatcher.group(IGenericFtraceConstants.FTRACE_SEPARATOR_GROUP);
                    attributes = attributes.substring(valStart);
                    keyvalMatcher = KEYVAL_KEY_PATTERN_MAP.getOrDefault(separator, KEYVAL_KEY_PATTERN).matcher(attributes);
                }

                if (key != null && valStart > 0) {
                    putKeyValueField(name, fields, key, attributes);
                }

                /*
                 * If anything else fails, but we have discovered sort of a valid event
                 * attributes lets just add the unparsed attributes with key "data".
                 */
                if (fields.isEmpty()) {
                    key = "data"; //$NON-NLS-1$
                    if (name.equals(IGenericFtraceConstants.FTRACE_EXIT_SYSCALL)) {
                        key = "ret"; //$NON-NLS-1$
                    }
                    fields.put(key, decodeString(attributes));
                }
            }

            return new GenericFtraceField(name, cpu, timestampInNano, pid, tid, fields);
        }
        return null;
    }

    private static void putKeyValueField(String name, Map<@NonNull String, @NonNull Object> fields, String key, String value) {
        String actualValue;
        Matcher valMatcher = KEYVAL_VALUE_PATTERN.matcher(value);
        if (!KEYVAL_VALUE_DOCKER_BYPASS.matcher(value).find() && valMatcher.matches()) {
            actualValue = valMatcher.group(KEYVAL_VALUE_GROUP).trim();
        } else {
            actualValue = value.trim();
        }
        if (!actualValue.trim().isEmpty()) {
            // This is a temporary solution. Refactor suggestions
            // are welcome.
            final GenericFtraceEventLayout eventLayout = GenericFtraceEventLayout.getInstance();
            if (key.equals(eventLayout.fieldPrevState())) {
                fields.put(key, parsePrevStateValue(actualValue));
            } else if (StringUtils.isNumeric(actualValue)) {
                String actualKey = key;
                if (key.equals("parent_pid") && name.equals(eventLayout.eventSchedProcessFork())) { //$NON-NLS-1$
                    actualKey = eventLayout.fieldTid();
                }
                fields.put(actualKey, Long.parseUnsignedLong(actualValue));
            } else {
                fields.put(key, decodeString(actualValue));
            }
        }
    }

    private static Object decodeString(String val) {
        try {
            if (val.startsWith("0x") || val.startsWith("0X")) { //$NON-NLS-1$ //$NON-NLS-2$
                // Chances are this is an hexadecimal string. Parse the value
                return Long.parseUnsignedLong(val.substring(2), 16);
            }
        } catch (NumberFormatException e) {
            // Fall back to returning the string
        }
        return val;
    }

    /**
     * Get the event content
     *
     * @return the event content
     */
    public ITmfEventField getContent() {
        return fContent;
    }

    /**
     * Set the event's content
     *
     * @param fields Map of field values
     */
    public void setContent(Map<String, Object> fields) {
        ITmfEventField[] array = fields.entrySet().stream()
                .map(entry -> new TmfEventField(entry.getKey(), entry.getValue(), null))
                .toArray(ITmfEventField[]::new);
        fContent = new TmfEventField(ITmfEventField.ROOT_FIELD_ID, fields, array);
    }

    /**
     * Get the name of the event
     *
     * @return the event name
     */
    public String getName() {
        return fName;
    }

    /**
     * Set the event's name
     *
     * @param name New name of the event
     */
    public void setName(String name) {
        fName = name;
    }

    /**
     * Get the TID of the event
     *
     * @return the event TID
     */
    public @Nullable Integer getTid() {
        return fTid;
    }

    /**
     * Set the TID of the event
     *
     * @param tid The new tid
     */
    public void setTid(Integer tid) {
        fTid = tid;
    }

    /**
     * Get the timestamp
     *
     * @return the timestamp in ns
     */
    public Long getTs() {
        return fTs;
    }

    /**
     * Get pid
     *
     * @return the process ID
     */
    @Nullable
    public Integer getPid() {
        return fPid;
    }

    /**
     * Set the PID of the event
     *
     * @param pid The new pid
     */
    public void setPid(Integer pid) {
        fPid = pid;
    }

    /**
     * Get the cpu number
     *
     * @return the cpu number
     */
    public Integer getCpu() {
        return fCpu;
    }

    /**
     * Parse the prev_state field on sched_switch event depending on whether it is a number or a character.
     *
     *
     * @return the state as a Long
     */
    private static Long parsePrevStateValue(String value) {
        Long state = 0L;
        if (StringUtils.isNumeric(value)) {
            state = Long.parseUnsignedLong(value);
        } else {
            state = PREV_STATE_LUT.getOrDefault(value.charAt(0), 0L);
        }
        return state;
    }

    /**
     * Searches for certain event names and rewrites them in order for different analysis to work.
     *
     *
     * @return the new or original event name
     */
    private static String eventNameRewrite(@Nullable String name, @Nullable String separator) {
        if (name == null) {
            return ""; //$NON-NLS-1$
        }

        /*
         * Rewrite syscall exit events to conform to syscall analysis.
         */
        if ((name.startsWith(IGenericFtraceConstants.FTRACE_SYSCALL_PREFIX) && separator != null && separator.equals(IGenericFtraceConstants.FTRACE_EXIT_SYSCALL_SEPARATOR)) ||
             name.startsWith(IGenericFtraceConstants.FTRACE_SYSCALL_EXIT_TRACECMD_PREFIX)
           ) {
            return IGenericFtraceConstants.FTRACE_EXIT_SYSCALL;
        }

        /*
         * Rewrite syscall enter from trace-cmd traces to conform to syscall analysis.
         */
        if (name.startsWith(IGenericFtraceConstants.FTRACE_SYSCALL_ENTER_TRACECMD_PREFIX)) {
            String newname = name.replaceFirst(IGenericFtraceConstants.FTRACE_SYSCALL_ENTER_TRACECMD_PREFIX, IGenericFtraceConstants.FTRACE_SYSCALL_PREFIX);
            if (newname != null) {
                return newname;
            }
        }

        return name;
    }
}
