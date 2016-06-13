package net.praqma.tracey.protocol.eiffel;

import net.praqma.tracey.protocol.eiffel.EiffelSourceChangeCreatedEventOuterClass.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.HunkHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;

public class EiffelSourceChangeCreatedEventFactory {
    private static final Logger log = Logger.getLogger( EiffelSourceChangeCreatedEventFactory.class.getName() );

    private static Repository openRepository(final String path) throws IOException {
        log.info("Attempting to read repo: " + path);
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        final Repository repository = builder.findGitDir(new File(path)).build();
        log.info("Read repository: " + repository.getDirectory());
        return repository;
    }

    private static RevCommit getCommitById(final Repository repository, final String commitId) throws IOException {
        final ObjectId commitObj = repository.resolve(commitId);
        log.info("Found commit: " + commitObj.getName());
        final RevWalk walk = new RevWalk(repository);
        final RevCommit commit = walk.parseCommit(commitObj);
        walk.dispose();
        return commit;
    }

    private static String parseChange(DiffEntry entry) {
        String result = "";
        switch (entry.getChangeType()) {
            case ADD:
            case MODIFY:
                result = entry.getChangeType().toString() + " " + entry.getNewPath();
                break;
            case DELETE:
                result = entry.getChangeType().toString() + " " + entry.getOldPath();
                break;
            case COPY:
            case RENAME:
                result = entry.getChangeType().toString() + " " + entry.getOldPath() + " => " + entry.getNewPath();
                break;
        }
        return result;
    }

    private static class PatchStat {
        public int insertions = 0;
        public int deletetions = 0;

        PatchStat() {};
    }

    private static PatchStat parseChangeStats(DiffFormatter df, DiffEntry entry) throws IOException {
        final PatchStat stat = new PatchStat();
        FileHeader fileHeader = df.toFileHeader(entry);
        List<? extends HunkHeader> hunks = fileHeader.getHunks();
        for (HunkHeader hunk : hunks) {
            for (Edit edit: hunk.toEditList()) {
                switch (edit.getType()) {
                    // An edit where beginA < endA && beginB == endB is a delete edit,
                    // that is sequence B has removed the elements between [beginA, endA).
                    case DELETE:
                        stat.deletetions += edit.getEndA() - edit.getBeginA();
                        break;
                    // An edit where beginA == endA && beginB < endB is an insert edit,
                    // that is sequence B inserted the elements in region [beginB, endB) at beginA.
                    case INSERT:
                        stat.insertions += edit.getEndB() - edit.getBeginB();
                        break;
                    // An edit where beginA < endA && beginB < endB is a replace edit,
                    // that is sequence B has replaced the range of elements between [beginA, endA) with those found in [beginB, endB).
                    case REPLACE:
                        stat.insertions += edit.getEndB() - edit.getBeginB();
                        stat.deletetions += edit.getEndA() - edit.getBeginA();
                        break;
                    case EMPTY:
                        break;
                }
                log.info("Calculated stat for change " + edit.toString() + ", insertions " + stat.insertions + ", deletions " + stat.deletetions);
            }
        }
        return stat;
    }

    private static EiffelSourceChangeCreatedEvent.Change getChange(final Repository repository, final RevCommit commit) throws IOException {
        final EiffelSourceChangeCreatedEvent.Change.Builder change = EiffelSourceChangeCreatedEvent.Change.newBuilder();
        PatchStat finalStat = new PatchStat();
        String fileChange = "";
        if ( commit.getParentCount() > 0 ) {
            // We commit.getParent(0) will return incomplete RevCommit that does not contain tree
            // That's why we should resolve parent using getCommitById. Yes, I it is silly but this is how it is
            RevCommit parent = getCommitById(repository, commit.getParent(0).getName());
            log.info("Get change info - compare " + commit.getName() + " and " + parent.getName());
            DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(repository);
            //df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);
            List<DiffEntry> diffs = df.scan(parent.getTree(), commit.getTree());
            for (DiffEntry diff : diffs) {
                fileChange = parseChange(diff);
                change.addFiles(fileChange);
                // Record change stats
                PatchStat stat = parseChangeStats(df, diff);
                finalStat.insertions += stat.insertions;
                finalStat.deletetions += stat.deletetions;
                log.info(fileChange + ", inserted lines: " + stat.insertions + ", deleted lines: " + stat.deletetions);
            }
        } else {
            // This means it is either a shallow clone or first commit in the tree
                log.warning("No parents found - can't get a diff");
        }
        change.setDeletions(finalStat.deletetions);
        change.setInsertions(finalStat.insertions);
        return change.build();
    }

    private static EiffelSourceChangeCreatedEvent.GitIdentifier getGitId(final Repository repository, final String commitId, final String branch) throws MalformedURLException {
        final EiffelSourceChangeCreatedEvent.GitIdentifier.Builder gitId = EiffelSourceChangeCreatedEvent.GitIdentifier.newBuilder();
        gitId.setBranch(branch);
        gitId.setCommitId(commitId);
        final Config storedConfig = repository.getConfig();
        // Pick first remote
        final Set<String> remotes = storedConfig.getSubsections("remote");
        if ( ! remotes.isEmpty() ) {
            final String uri = storedConfig.getString("remote", remotes.iterator().next(), "url");
            gitId.setRepoUri(uri);
            // Get last part of the URI as a repo name
            gitId.setRepoName(uri.substring(uri.lastIndexOf('/') + 1).replaceAll(".git$", ""));
        } else {
            log.warning("No remotes configure for the repo " + repository.getDirectory() + " . Can't read remote url");
        }
        return gitId.build();
    }

    private static EiffelSourceChangeCreatedEvent.Author getAuthor(final RevCommit commit) {
        final EiffelSourceChangeCreatedEvent.Author.Builder author = EiffelSourceChangeCreatedEvent.Author.newBuilder();
        author.setEmail(commit.getAuthorIdent().getEmailAddress());
        author.setName(commit.getAuthorIdent().getName());
        // TODO: Have no idea where to get organisation and id
        return author.build();
    }

    // TODO: parse issues from the commit message
    private static List<EiffelSourceChangeCreatedEvent.Issue> getIssues(final RevCommit commit) {
        List<EiffelSourceChangeCreatedEvent.Issue> issues = new ArrayList<>();
        return issues;
    }

    public static EiffelSourceChangeCreatedEvent createFromGit(final String path, final String commitId, final String branch) throws IOException {
        final EiffelSourceChangeCreatedEvent.Builder eventData = EiffelSourceChangeCreatedEvent.newBuilder();
        final Repository repository = openRepository(path);
        final RevCommit commit = getCommitById(repository, commitId);
        eventData.setGitIdentifier(getGitId(repository, commitId, branch));
        eventData.setAuthor(getAuthor(commit));
        eventData.addAllIssues(getIssues(commit));
        eventData.setChange(getChange(repository, commit));
        return eventData.build();
    }
}
