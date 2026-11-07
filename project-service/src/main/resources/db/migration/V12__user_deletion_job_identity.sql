CREATE UNIQUE INDEX project_user_deletion_job ON project.project_write_fence(deletion_job_id)
    WHERE scope='USER' AND deletion_job_id IS NOT NULL;
