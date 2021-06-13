/*******************************************************************************
 * Copyright (c) 2009, 2021 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *    Kyle Lieber - implementation of CheckMojo
 *
 *******************************************************************************/
package org.jacoco.maven;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.CommentsCollection;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.javadoc.JavadocBlockTag;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jacoco.report.IReportGroupVisitor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.stream.Collectors.*;

/**
 * 按照日期和作者聚合的测试覆盖率报告
 * @author czt
 * @since 2021/06/10
 */
@Mojo(name = "date-author-aggregate-report", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class DateAuthorAggregateReportMojo extends ReportAggregateMojo {
	private static final String JAVA_FILE_SUFFIX = ".java";
	private static final String CLASS_FILE_SUFFIX = ".class";

	private static final DateFormat yyyyMMdd = new SimpleDateFormat("yyyyMMdd");

	/**
	 * 日期tag名称
	 */
	@Parameter(defaultValue = "date", required = true)
	private String dateTagName;

	/**
	 * 日期模式
	 */
	@Parameter(defaultValue = "yyyy/M/d,yyyy-M-d,yyyy年M月d日", required = true)
	private String[] datePatterns;

	/**
	 * 作者tag名称
	 */
	@Parameter(defaultValue = "author", required = true)
	private String authorTagName;

	/**
	 * 作者姓名分隔符，默认为斜杠'/'或者空格' '
	 */
	@Parameter(defaultValue = "/", required = true)
	private String[] authorDelimiters;

	/**
	 * 基准日期，格式为yyyy-MM-dd，如果设置了基准日期则只计算创建日期大于或等于基准日期的类文件的单元测试覆盖率
	 */
	@Parameter
	private String baselineDate;

	/**
	 * 是否聚合多个项目
	 */
	@Parameter(defaultValue = "false")
	private boolean aggregateProjects;

	public DateAuthorAggregateReportMojo() {
	}

	/**
	 * 块注释中的tag信息
	 */
	private static class CommentTags {
		private final String date;
		private final String author;

		public CommentTags(String date, String author) {
			this.date = date;
			this.author = author;
		}
	}

	/**
	 * 原始class文件信息
	 */
	private static class RawClassFile {
		private final String yearMonth; // 创建日期（yyyy年MM月）
		private final String author; // 作者
		private final File directory; // 文件目录
		private final String pathname; // 文件路径，不带后缀名

		public RawClassFile(String yearMonth, String author, File directory, String pathname) {
			this.yearMonth = yearMonth;
			this.author = author;
			this.directory = directory;
			this.pathname = pathname;
		}

		public File getDirectory() {
			return directory;
		}
	}

	/**
	 * class文件信息
	 */
	private static class ClassFile {
		private final String yearMonth; // 创建日期（yyyy年MM月）
		private final String author; // 作者
		private final File file; // class文件对象

		public ClassFile(String yearMonth, String author, File file) {
			this.yearMonth = yearMonth;
			this.author = author;
			this.file = file;
		}

		public String getYearMonth() {
			return yearMonth;
		}

		public String getAuthor() {
			return author;
		}

		public File getFile() {
			return file;
		}
	}

	private String reformatDate(String date) {
		if (StringUtils.isEmpty(date)) {
			return null;
		}
		try {
			return yyyyMMdd.format(DateUtils.parseDate(date, datePatterns));
		} catch (Exception e) {
			return null;
		}
	}

	private String extractAuthorName(String authorTagContent) {
		for (String authorDelimiter: authorDelimiters) {
			if (authorTagContent.contains(authorDelimiter)) {
				return authorTagContent.substring(0, authorTagContent.indexOf(authorDelimiter));
			}
		}
		return authorTagContent;
	}

	private CommentTags getJavaFileCommentTags(File file) {
		try {
			ParseResult<CompilationUnit> parseResult = new JavaParser().parse(file);
			Set<JavadocComment> comments = parseResult.getCommentsCollection()
					.map(CommentsCollection::getJavadocComments)
					.orElse(Collections.emptySet());
			String date = null;
			String author = null;
			for (JavadocComment comment : comments) {
				for (JavadocBlockTag tag : comment.parse().getBlockTags()) {
					if (date == null && tag.getTagName().equals(dateTagName)) {
						date = reformatDate(tag.getContent().toText());
					}
					if (author == null && tag.getTagName().equals(authorTagName)) {
						author = extractAuthorName(tag.getContent().toText());
					}
				}
				if (date != null && author != null) {
					break;
				}
			}
			if (StringUtils.isEmpty(date) || StringUtils.isEmpty(author)) {
				getLog().warn(String.format("Java source file missing @%s or @%s tags: %s", dateTagName,
						authorTagName, file.getPath()));
			}
			return new CommentTags(date, author);
		} catch (FileNotFoundException e) {
			getLog().error(e.getMessage());
			return new CommentTags(null, null);
		}
	}

	/**
	 * 按照文件夹聚合class文件
	 */
	private Map<File, List<RawClassFile>> groupClassFileByDirectory(MavenProject project) throws IOException {
		FileFilter filter = new FileFilter(includes, excludes);
		String sourceDirectory = project.getBuild().getSourceDirectory();
		String outputDirectory = project.getBuild().getOutputDirectory();
		String baselineDate = reformatDate(this.baselineDate);
		return filter.getFiles(new File(sourceDirectory))
			.parallelStream()
			.filter(file -> file.getPath().endsWith(JAVA_FILE_SUFFIX))
			.map(javaFile -> {
				CommentTags commentTags = getJavaFileCommentTags(javaFile);
				// 忽略作日期或者作者信息为空的文件
				if (StringUtils.isEmpty(commentTags.date) || StringUtils.isEmpty(commentTags.author)) {
					return null;
				}
				// 如果设置了基准日期，忽略文件日期小于基准日期
				if (!StringUtils.isEmpty(baselineDate) && commentTags.date.compareTo(baselineDate) < 0) {
					return null;
				}
				// 转换为yyyy年MM月格式
				String yearMonth = commentTags.date.substring(0, 4) + "年" + commentTags.date.substring(4, 6) + "月";
				// 替换文件路径前缀
				String pathname = outputDirectory + StringUtils.removeStart(javaFile.getPath(), sourceDirectory);
				// 移除后缀名.java
				String classFilePathname = StringUtils.removeEnd(pathname, JAVA_FILE_SUFFIX);
				// 父目录
				File directory = new File(classFilePathname + CLASS_FILE_SUFFIX).getParentFile();
				return new RawClassFile(yearMonth, commentTags.author, directory, classFilePathname);
			})
			.filter(Objects::nonNull)
			.collect(groupingByConcurrent(RawClassFile::getDirectory));
	}

	/**
	 * 按照日期和作者聚合class文件
	 */
	private Map<String, Map<String, List<File>>> aggregateProjectClassFiles(MavenProject project) throws IOException {
		Map<File, List<RawClassFile>> groupedClassFiles = groupClassFileByDirectory(project);
		Map<String, Map<String, List<File>>> aggregatedClassFiles = groupedClassFiles.entrySet()
			.parallelStream()
			.flatMap(entry -> {
				File directory = entry.getKey();
				File[] filesInDirectory = ObjectUtils.defaultIfNull(directory.listFiles(), new File[0]);
				return entry.getValue().stream().flatMap(rawClassFile -> {
					String classFilePathname = rawClassFile.pathname + CLASS_FILE_SUFFIX; // class文件本身
					String innerClassFilePathnamePrefix = rawClassFile.pathname + "$"; // 内部类、匿名内部类文件
					return Arrays.stream(filesInDirectory)
						.filter(file -> {
							String path = file.getPath();
							// 查找原始class文件和其中定义的内部类、匿名内部类文件
							return path.equals(classFilePathname) || path.startsWith(innerClassFilePathnamePrefix);
						})
						.map(file -> {
							getLog().info("Found class file: " + file.getPath());
							return new ClassFile(rawClassFile.yearMonth, rawClassFile.author, file);
						});
				});
			})
			.collect(
				groupingByConcurrent( // 提升性能
					ClassFile::getYearMonth,
					groupingBy(
						ClassFile::getAuthor,
						ConcurrentHashMap::new, // 提升性能
						mapping(ClassFile::getFile, toList())
					)
				)
			);
		Set<String> authors = aggregatedClassFiles.values().stream().map(Map::keySet)
				.flatMap(Set::stream).collect(toSet());
		getLog().info("Java source file authors: " + authors);
		return aggregatedClassFiles;
	}

	private List<MavenProject> findDependencies() {
		return findDependencies(Artifact.SCOPE_COMPILE, Artifact.SCOPE_RUNTIME, Artifact.SCOPE_PROVIDED);
	}

	@Override
	void createReport(IReportGroupVisitor visitor, ReportSupport support) throws IOException {
		getLog().info("Start generating date author aggregate coverage report");
		long start = System.currentTimeMillis();
		IReportGroupVisitor group = visitor.visitGroup(title);
		// 打包方式为pom时，默认聚合多个项目
		if (aggregateProjects || project.getPackaging().equalsIgnoreCase("pom")) {
			for (MavenProject dependency : findDependencies()) {
				createReport(group.visitGroup(dependency.getArtifactId()), dependency, support);
			}
		} else {
			createReport(group, project, support);
		}
		double seconds = (System.currentTimeMillis() - start) / 1000.0;
		getLog().info("------------------------------------------------------------------------");
		getLog().info(String.format("Generate date author aggregate coverage report in %.3f s", seconds));
		getLog().info("------------------------------------------------------------------------");
	}

	private void createReport(IReportGroupVisitor group, MavenProject project, ReportSupport support)
			throws IOException {
		Map<String, Map<String, List<File>>> aggregatedClassFiles = aggregateProjectClassFiles(project);
		for (Entry<String, Map<String, List<File>>> outerEntry : aggregatedClassFiles.entrySet()) {
			String yearMonth = outerEntry.getKey();
			Map<String, List<File>> authorFiles = outerEntry.getValue();
			IReportGroupVisitor childGroup = group.visitGroup(yearMonth);
			for (Entry<String, List<File>> innerEntry : authorFiles.entrySet()) {
				String author = innerEntry.getKey();
				List<File> files = innerEntry.getValue();
				support.processProject(childGroup, author, project, files, sourceEncoding);
			}
		}
	}
}
