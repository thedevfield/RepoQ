CREATE INDEX interview_session_free_history ON interview.interview_session
    (owner_user_id, project_id, created_at DESC, id DESC) WHERE mode='FREE';
